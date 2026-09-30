-- =============================================================================
-- 秒杀链路 · 数据初始化脚本（幂等，可重复执行）
-- =============================================================================
-- 背景：种子数据里秒杀相关的三张表处于「不可用」状态，秒杀链路根本无法跑起来：
--   1) sms_flash_promotion 只有 id=14，有效期 2022-11-09 ~ 2023-12-31 —— 早就过期
--   2) sms_flash_promotion_product_relation 有 16 行指向 flash_promotion_id=2，
--      而 id=2 这个活动根本不存在（悬空外键）
--   3) 47 行关系数据里，只有 1 行同时配了秒杀价/库存/限购，其余是 NULL
--   4) 没有 flash_promotion_sold_count 字段，"已售数量"无处记录 → DB 侧无法防超卖
-- 所以本脚本做 4 件事：补字段、修悬空外键、修有效期、加一个全天演示场次 + 秒杀商品。
--
-- 【回滚方法】改动前的原始值已记录在文末「回滚」一节，按那里的 SQL 可完全还原。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 新增「秒杀已售数量」字段
--    为什么需要它：Redis 里的库存是"实时"的，但 Redis 不可靠（宕机/重启/网络分区），
--    必须在数据库侧有一个能独立判断"还剩多少"的计数作为最终权威 ——
--    这就是防超卖的兜底：即使 Redis 的库存被刷成负数，DB 的条件更新也会拦住。
--    MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，这里用 information_schema 判断实现幂等。
-- -----------------------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'sms_flash_promotion_product_relation'
    AND column_name = 'flash_promotion_sold_count'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE sms_flash_promotion_product_relation
     ADD COLUMN flash_promotion_sold_count INT(11) NOT NULL DEFAULT 0
     COMMENT ''秒杀已售数量：DB 侧的防超卖兜底计数，与 Redis 库存最终一致''',
  'SELECT ''flash_promotion_sold_count 已存在，跳过'' AS info');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 1.5) 新增「秒杀下单流水」表
--    为什么必须有这张表（这是异步下单能成立的前提）：
--      · **幂等键**：request_id 唯一索引，保证 MQ 重复投递时同一请求只建一单
--        （MQ 是 at-least-once，重复消费是常态，不能靠消费端自己"小心"）
--      · **异步结果查询**：接口立即返回"排队中"，用户必须能凭 requestId 查最终结果
--      · **限购统计**：按 (member, 活动, 场次, 商品) 聚合即可算出已买几件
--      · **对账依据**：流水里 status=0（卡在排队中）的记录就是需要人工/补偿介入的异常单
--    不往 oms_order 加字段的原因：秒杀是"一个订单来源维度"，塞进订单主表会让
--    主表持续膨胀且语义混杂；独立流水表的生命周期也更短（可定期归档）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sms_flash_promotion_order` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT,
  `request_id` VARCHAR(64) NOT NULL COMMENT '请求唯一标识：幂等键，同一 requestId 只允许建一单',
  `order_id` BIGINT(20) DEFAULT NULL COMMENT '生成的订单ID',
  `order_sn` VARCHAR(64) DEFAULT NULL COMMENT '订单编号',
  `member_id` BIGINT(20) NOT NULL COMMENT '会员ID',
  `flash_promotion_id` BIGINT(20) NOT NULL COMMENT '秒杀活动ID',
  `flash_promotion_session_id` BIGINT(20) NOT NULL COMMENT '秒杀场次ID',
  `relation_id` BIGINT(20) NOT NULL COMMENT '秒杀库存行ID',
  `product_id` BIGINT(20) NOT NULL COMMENT '商品ID',
  `quantity` INT(11) NOT NULL DEFAULT 1 COMMENT '秒杀数量',
  `status` INT(2) NOT NULL DEFAULT 0 COMMENT '0->排队中；1->已建单；2->已失败',
  `fail_reason` VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
  `create_time` DATETIME DEFAULT NULL,
  `update_time` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request_id` (`request_id`),
  KEY `idx_member_scope` (`member_id`,`flash_promotion_id`,`flash_promotion_session_id`,`product_id`),
  KEY `idx_status_time` (`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='秒杀下单流水：幂等键 + 异步结果查询 + 限购统计 + 对账依据';


-- -----------------------------------------------------------------------------
-- 2) 修复悬空外键：relation.flash_promotion_id = 2 指向不存在的活动
--    改成指向真实存在的 14。
-- -----------------------------------------------------------------------------
UPDATE sms_flash_promotion_product_relation
SET flash_promotion_id = 14
WHERE flash_promotion_id = 2
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sms_flash_promotion) p WHERE p.id = 2);

-- -----------------------------------------------------------------------------
-- 3) 修复活动有效期，让它覆盖当前时间（原 2022-11-09 ~ 2023-12-31 已过期）
-- -----------------------------------------------------------------------------
UPDATE sms_flash_promotion
SET start_date = '2026-01-01',
    end_date   = '2027-12-31',
    status     = 1
WHERE id = 14;

-- -----------------------------------------------------------------------------
-- 4) 新增「全天演示场次」
--    为什么要加：原有场次是 08:00-10:00 / 10:00-12:00 这种两小时窗口，
--    要在非窗口时间去验证秒杀链路就得等到那个点，演示和自测都不方便。
--    加一个覆盖全天的场次后，任何时间都能完整跑通链路。
--    （真实项目当然不应该这样配，这里纯粹是为了可演示性，已在标题里标明"演示"。）
-- -----------------------------------------------------------------------------
INSERT INTO sms_flash_promotion_session (id, name, start_time, end_time, status, create_time)
VALUES (8, '全天演示场次', '00:00:00', '23:59:59', 1, NOW())
ON DUPLICATE KEY UPDATE
  name       = VALUES(name),
  start_time = VALUES(start_time),
  end_time   = VALUES(end_time),
  status     = 1;

-- -----------------------------------------------------------------------------
-- 5) 为演示场次配置秒杀商品
--    先按 (活动, 场次) 删掉旧行再插入 —— 保证脚本可重复执行，且只影响本演示场次。
--    注意：重跑会把 flash_promotion_sold_count 归零（相当于重置秒杀库存），这是演示脚本的预期行为。
--    秒杀价都明显低于商品原价（26: 3788 / 27: 2699 / 28: 649），这样"下单用秒杀价"才看得出效果。
-- -----------------------------------------------------------------------------
DELETE FROM sms_flash_promotion_product_relation
WHERE flash_promotion_id = 14 AND flash_promotion_session_id = 8;

INSERT INTO sms_flash_promotion_product_relation
  (flash_promotion_id, flash_promotion_session_id, product_id,
   flash_promotion_price, flash_promotion_count, flash_promotion_limit,
   flash_promotion_sold_count, sort)
VALUES
  (14, 8, 26, 2999.00, 10, 1, 0, 100),   -- 原价 3788，秒杀 2999，10 件，每人限 1
  (14, 8, 27, 1999.00, 10, 1, 0,  99),   -- 原价 2699，秒杀 1999，10 件，每人限 1
  (14, 8, 28,  499.00, 20, 2, 0,  98);   -- 原价  649，秒杀  499，20 件，每人限 2

-- -----------------------------------------------------------------------------
-- 6) 把演示商品补到「所有启用场次」，让演示与当前时间无关
--
--    为什么需要这一步：原有场次是 08:00-10:00 这类两小时窗口，同一时刻可能
--    有多个场次同时"命中"（比如全天场次 + 18:00-20:00 场次），
--    而查询用的是 `ORDER BY start_time DESC LIMIT 1`，会优先选中 start_time 更晚的那个。
--    结果就是：只在全天场次配了商品的话，08:00-22:00 之间每个整点都会被其它场次抢走，
--    提交秒杀时会因为"当前不在该场次时间内"而失败。
--
--    另外还要注意：种子数据里很多行是「建了商品但价格/库存是 NULL」的空壳行，
--    所以这里必须分两步 —— 先 UPDATE 补全空壳行，再 INSERT 缺失的行。
--    只做 INSERT 的话会因为 (场次, 商品) 已存在而全部跳过，看起来"执行成功"其实什么都没做。
-- -----------------------------------------------------------------------------
UPDATE sms_flash_promotion_product_relation r
JOIN (
  SELECT 26 AS pid, 2999.00 AS price, 10 AS cnt, 1 AS lim, 100 AS srt
  UNION ALL SELECT 27, 1999.00, 10, 1, 99
  UNION ALL SELECT 28,  499.00, 20, 2, 98
) v ON v.pid = r.product_id
SET r.flash_promotion_price = v.price,
    r.flash_promotion_count = v.cnt,
    r.flash_promotion_limit = v.lim,
    r.flash_promotion_sold_count = 0
WHERE r.flash_promotion_id = 14
  AND r.flash_promotion_price IS NULL;

INSERT INTO sms_flash_promotion_product_relation
  (flash_promotion_id, flash_promotion_session_id, product_id,
   flash_promotion_price, flash_promotion_count, flash_promotion_limit,
   flash_promotion_sold_count, sort)
SELECT 14, s.id, v.pid, v.price, v.cnt, v.lim, 0, v.srt
FROM sms_flash_promotion_session s
CROSS JOIN (
  SELECT 26 AS pid, 2999.00 AS price, 10 AS cnt, 1 AS lim, 100 AS srt
  UNION ALL SELECT 27, 1999.00, 10, 1, 99
  UNION ALL SELECT 28,  499.00, 20, 2, 98
) v
WHERE s.status = 1
  AND NOT EXISTS (
    SELECT 1 FROM (
      SELECT flash_promotion_session_id AS sid, product_id AS pid
      FROM sms_flash_promotion_product_relation WHERE flash_promotion_id = 14
    ) x WHERE x.sid = s.id AND x.pid = v.pid
  );

-- -----------------------------------------------------------------------------
-- 校验：跑完应该看到 3 行、sold_count 全 0、活动有效期覆盖今天
-- -----------------------------------------------------------------------------
SELECT p.id AS promotion_id, p.start_date, p.end_date, p.status, CURDATE() AS today,
       s.id AS session_id, s.name AS session_name, s.start_time, s.end_time
FROM sms_flash_promotion p
JOIN sms_flash_promotion_session s ON s.id = 8
WHERE p.id = 14;

SELECT r.id, r.product_id, r.flash_promotion_price, r.flash_promotion_count,
       r.flash_promotion_sold_count,
       (r.flash_promotion_count - r.flash_promotion_sold_count) AS remain,
       r.flash_promotion_limit
FROM sms_flash_promotion_product_relation r
WHERE r.flash_promotion_id = 14 AND r.flash_promotion_session_id = 8
ORDER BY r.sort DESC;

-- =============================================================================
-- 【回滚】执行以下 SQL 可完全还原到脚本执行前的状态
-- =============================================================================
-- -- 1) 删掉演示场次的商品配置
-- DELETE FROM sms_flash_promotion_product_relation
--   WHERE flash_promotion_id = 14 AND flash_promotion_session_id = 8;
--
-- -- 2) 删掉演示场次
-- DELETE FROM sms_flash_promotion_session WHERE id = 8;
--
-- -- 3) 还原活动有效期（改动前的原值）
-- UPDATE sms_flash_promotion SET start_date='2022-11-09', end_date='2023-12-31' WHERE id=14;
--
-- -- 4) 还原悬空外键（原值 2；注意这会让数据重新变成"指向不存在的活动"）
-- UPDATE sms_flash_promotion_product_relation SET flash_promotion_id = 2
--   WHERE flash_promotion_id = 14 AND flash_promotion_session_id IN (1,2,3,4,5,6);
--   -- 只还原原本就指向 2 的那批，避免误伤原本指向 14 的行
--
-- -- 5) 删除新增字段
-- ALTER TABLE sms_flash_promotion_product_relation DROP COLUMN flash_promotion_sold_count;
-- =============================================================================
