-- =============================================================================
-- 商品评价 · 演示数据（幂等，可重复执行）
-- =============================================================================
-- 目的：pms_comment / pms_comment_replay 原来是两张空表，没有任何数据，
--       评价功能即使写好了也没法验证。本脚本造一批「结构化但看起来真实」的评价。
--
-- ★ 关键约束：**评价必须来自真实的已完成订单。**
--   不能随便编 (member_id, product_id) 组合，否则会造出"用户评价了自己没买过的东西"，
--   这种数据在验证「只能评价已购买商品」这条规则时会自相矛盾 ——
--   接口说不能评，但数据里明明有，排查时会被误导。
--   所以下面全部从 oms_order(status=3) + oms_order_item 里取真实组合。
--
-- 【回滚】见文末。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0) 【前置】补齐「有明细的已完成订单」
--
-- 为什么需要这一步：
--   oms_order 里 status=3 的订单有 33 万条、涉及大量会员，看起来数据很足；
--   但**其中带 oms_order_item 明细的只有 16 条** —— 早前批量造的测试订单
--   只写了主表、没写明细。
--   而「评价资格」的判定必须是 `订单已完成 AND 订单包含该商品`，两者缺一不可，
--   所以可用的 (会员, 商品) 组合只有十几个，评价数据根本造不多。
--
--   这里给一批「已完成但无明细」的订单补上明细，把组合数扩到够用的量级。
--   这是**补齐缺失的明细**，不是伪造新订单 —— 订单本身早已存在且状态就是已完成。
--
--   ★ 只处理 60 条，且用 product_id 与订单 id 双条件限定，方便精确回滚（见文末）。
--     顺手打个标记：product_name 加 '[SEED]' 前缀，回滚时按它精确删除。
-- -----------------------------------------------------------------------------
INSERT INTO oms_order_item
  (order_id, order_sn, product_id, product_name, product_pic, product_sku_id,
   product_price, product_quantity, product_sku_code, product_category_id,
   promotion_name, promotion_amount, coupon_amount, integration_amount,
   real_amount, gift_integration, gift_growth)
SELECT
  t.order_id,
  t.order_sn,
  t.product_id,
  CONCAT('[SEED] ', LEFT(IFNULL(p.name, '演示商品'), 100)),
  p.pic,
  (SELECT MIN(s.id) FROM pms_sku_stock s WHERE s.product_id = t.product_id),
  p.price, 1, NULL, p.product_category_id,
  NULL, 0, 0, 0, p.price, 0, 0
FROM (
  SELECT o.id AS order_id, o.order_sn,
         -- 让每个订单配一个商品，并在若干商品间轮转，制造更多 (会员, 商品) 组合
         (26 + (o.id % 15)) AS product_id,
         @rownum := @rownum + 1 AS rn
  FROM oms_order o
  CROSS JOIN (SELECT @rownum := 0) r
  WHERE o.status = 3
    AND o.member_id > 0
    AND NOT EXISTS (SELECT 1 FROM oms_order_item i WHERE i.order_id = o.id)
  ORDER BY o.id DESC
  LIMIT 60
) t
JOIN pms_product p ON p.id = t.product_id;

-- -----------------------------------------------------------------------------
-- 0.5) 【必须】把 /comment/** 注册进动态权限（ums_resource）
--
-- ★ 不注册会怎样：后台接口一律 403「没有相关权限」。
--
-- 原因在 mall-security 的 DynamicAuthorizationManager.check()：
--     needAuthorities = 该 URL 匹配到的资源权限列表
--     若 URL 没注册过 → needAuthorities 是**空列表**
--     → filter(item -> needAuthorities.contains(...)) 结果必为空
--     → hasAuth 为空 → return new AuthorizationDecision(false) → 403
--
-- 也就是说 mall-admin 对「未注册的接口」是 **fail-closed（默认拒绝）**。
-- 这是个好设计（新接口忘了配权限时是拒绝而非放行），但代价是：
-- **每新增一个后台接口，都必须往 ums_resource 注册 + 授权给角色**，否则它永远调不通。
-- 这条约束很容易被忽略 —— 接口写完了、编译过了、一调就 403，还以为是代码问题。
--
-- 【重要】注册完必须**重启 mall-admin**：动态权限是在启动时
--         @PostConstruct 一次性加载进内存的（configAttributeMap），
--         改库不会自动刷新。
-- -----------------------------------------------------------------------------
DELETE FROM ums_role_resource_relation
WHERE resource_id IN (SELECT id FROM (SELECT id FROM ums_resource WHERE url = '/comment/**') t);
DELETE FROM ums_resource WHERE url = '/comment/**';

INSERT INTO ums_resource (category_id, name, url, description, create_time)
VALUES (1, '商品评价管理', '/comment/**', '后台评价审核、回复、删除', NOW());

SET @rid = LAST_INSERT_ID();

-- 授权给 超级管理员(5) / 商品管理员(1) / 订单管理员(2)
INSERT INTO ums_role_resource_relation (role_id, resource_id)
SELECT r.id, @rid FROM ums_role r WHERE r.id IN (5, 1, 2);

SELECT @rid AS comment_resource_id;

-- -----------------------------------------------------------------------------
-- 0.6) 【必须】清掉 Redis 里的「管理员资源缓存」，否则上面注册的资源不生效
--
-- ★★ 这一步比注册更容易被漏掉，因为它"看起来不像必须的"。
--
-- 链路是这样的（UmsAdminServiceImpl.getResourceList）：
--     List<UmsResource> list = cacheService.getResourceList(adminId);  // 先查 Redis
--     if (CollUtil.isNotEmpty(list)) return list;                      // 命中就直接返回
--     ...否则查库并回填缓存
--
-- 而 AdminUserDetails.getAuthorities() 是把这份资源列表映射成权限：
--     resource.getId() + ":" + resource.getName()
--
-- 所以：**注册了新资源但没清缓存 → 新资源不在用户的权限列表里 → 接口永远 403**。
-- 而登录本身是成功的、token 也是有效的，报错却是"没有相关权限" ——
-- 极易误导人去查代码，而不是查缓存。
--
-- 顺带说明：这套缓存是**被动失效**的 —— 只有通过后台"资源管理"界面改资源时
-- 才会触发清理。像本脚本这样直接写库，就必须手动清。
--
-- 【执行方式】SQL 里动不了 Redis，需要单独执行（脚本末尾会打印提示）：
--    docker exec mall-redis redis-cli --scan --pattern 'mall:ums:resourceList:*' | xargs -r docker exec mall-redis redis-cli DEL
-- 然后**重新登录**（因为权限是在构造 UserDetails 时算好的）。
-- 另外注册资源后还需**重启 mall-admin**：动态权限规则本身也是启动时一次性加载的。
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 1) 清理旧的演示数据（只清本脚本造的：评价内容带 [DEMO] 前缀）
-- -----------------------------------------------------------------------------
DELETE FROM pms_comment_replay
WHERE comment_id IN (SELECT id FROM (SELECT id FROM pms_comment WHERE content LIKE '[DEMO]%') t);

DELETE FROM pms_comment WHERE content LIKE '[DEMO]%';

-- -----------------------------------------------------------------------------
-- 2) 造评价
--    从「已完成订单」里取 (member_id, product_id) 组合，每个组合造一条评价。
--    星级按 id 取模分配，保证 1-5 星都有 —— 这样好评率和星级分布才有东西可算，
--    否则全是 5 星，"好评率"就永远显示 100%，等于没验证到。
-- -----------------------------------------------------------------------------
INSERT INTO pms_comment
  (product_id, member_id, member_nick_name, member_icon, product_name,
   star, member_ip, create_time, show_status, product_attribute,
   collect_couont, read_count, content, pics, replay_count)
SELECT
  x.product_id,
  x.member_id,
  CONCAT('用户', LPAD(x.member_id, 4, '0')),
  NULL,
  x.product_name,
  -- 星级：让 5 星最多（符合真实分布的偏态），但要保证 1-4 星也都有样本
  CASE x.rn % 10
    WHEN 0 THEN 1
    WHEN 1 THEN 2
    WHEN 2 THEN 3
    WHEN 3 THEN 4
    WHEN 4 THEN 4
    ELSE 5
  END AS star,
  '127.0.0.1',
  DATE_SUB(NOW(), INTERVAL (x.rn * 7) HOUR),
  1,                                   -- show_status = 1：默认可见
  '颜色:标准版;规格:官方标配',
  x.rn % 13,                           -- collect_couont：点赞数
  x.rn % 57,                           -- read_count：阅读数
  CONCAT('[DEMO] ',
    CASE x.rn % 5
      WHEN 0 THEN '收到货有点小瑕疵，客服处理还算及时，总体一般。'
      WHEN 1 THEN '性价比不错，做工比预期好，物流也快。'
      WHEN 2 THEN '第二次买了，家里人都用这个牌子，稳定。'
      WHEN 3 THEN '包装完好，功能正常，用了几天没发现问题。'
      ELSE '非常满意！比同价位的其他型号强不少，推荐。'
    END),
  NULL,
  0
FROM (
  SELECT
    oi.product_id,
    o.member_id,
    MAX(oi.product_name) AS product_name,
    @rownum := @rownum + 1 AS rn
  FROM oms_order o
  JOIN oms_order_item oi ON oi.order_id = o.id
  CROSS JOIN (SELECT @rownum := 0) r
  WHERE o.status = 3                      -- 3 = 已完成
    AND o.member_id > 0
    AND oi.product_id IS NOT NULL
  GROUP BY oi.product_id, o.member_id
  ORDER BY oi.product_id, o.member_id
  LIMIT 60                                -- 控制造数规模，够验证即可
) x;

-- -----------------------------------------------------------------------------
-- 3) 给一部分评价加「商家回复」
--    只回复 4 星以下的（差评）—— 真实运营就是这样：好评不用回，差评必须回。
--    回复数一并写回 pms_comment.replay_count，保证计数与明细一致
--    （手动造数时最容易漏的就是这一步，结果列表显示"0 条回复"却点开有内容）
-- -----------------------------------------------------------------------------
INSERT INTO pms_comment_replay
  (comment_id, member_nick_name, member_icon, content, create_time, type)
SELECT
  c.id,
  '官方客服',
  NULL,
  CONCAT('您好，非常抱歉给您带来不好的体验。关于「',
         LEFT(IFNULL(c.content, ''), 20),
         '...」的问题，我们已记录并反馈给产品部门，可联系客服为您安排退换。'),
  DATE_ADD(c.create_time, INTERVAL 6 HOUR),
  1                                        -- 1 = 商家回复
FROM pms_comment c
WHERE c.content LIKE '[DEMO]%'
  AND c.star <= 3;

-- 同步回复数（必须与上一步插入了多少条回复保持一致）
UPDATE pms_comment c
SET c.replay_count = (
  SELECT COUNT(*) FROM (SELECT id, comment_id FROM pms_comment_replay) r
  WHERE r.comment_id = c.id
)
WHERE c.content LIKE '[DEMO]%';

-- -----------------------------------------------------------------------------
-- 4) 校验：跑完应该看到评价数与星级分布
-- -----------------------------------------------------------------------------
SELECT
    COUNT(*)                                   AS total_cnt,
    SUM(star = 5)                              AS star5,
    SUM(star = 4)                              AS star4,
    SUM(star = 3)                              AS star3,
    SUM(star = 2)                              AS star2,
    SUM(star = 1)                              AS star1,
    ROUND(SUM(star >= 4) * 100 / COUNT(*), 1)  AS good_rate
FROM pms_comment WHERE content LIKE '[DEMO]%';

SELECT COUNT(*) AS seeded_items FROM oms_order_item WHERE product_name LIKE '[SEED] %';

SELECT COUNT(*) AS replay_cnt FROM pms_comment_replay WHERE member_nick_name = '官方客服';

-- 计数与明细是否一致（应全为 0）—— 造数时最容易漏的一步
SELECT COUNT(*) AS mismatch_cnt
FROM pms_comment c
WHERE c.content LIKE '[DEMO]%'
  AND c.replay_count <> (SELECT COUNT(*) FROM pms_comment_replay r WHERE r.comment_id = c.id);

-- =============================================================================
-- 【回滚】
-- DELETE FROM pms_comment_replay WHERE member_nick_name = '官方客服';
-- DELETE FROM pms_comment WHERE content LIKE '[DEMO]%';
--
-- 回滚「补齐的订单明细」（按 [SEED] 前缀精确删除，不影响真实明细）
-- DELETE FROM oms_order_item WHERE product_name LIKE '[SEED] %';
--
-- 回滚「注册的动态权限」
-- DELETE FROM ums_role_resource_relation WHERE resource_id IN (SELECT id FROM ums_resource WHERE url='/comment/**');
-- DELETE FROM ums_resource WHERE url = '/comment/**';
-- （只删本脚本造的数据，不影响真实评价）
-- =============================================================================
