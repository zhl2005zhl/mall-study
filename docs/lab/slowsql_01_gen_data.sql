-- ============================================================
-- 慢 SQL 案例 · 第 1 步：造数据放大
-- 目标：把 oms_order 放大到 200 万行，让"缺索引"的代价真正暴露出来
--
-- 用法：
--   docker exec -i mall-mysql mysql -uroot -proot mall < slowsql_01_gen_data.sql
--
-- 特点：可重复运行 —— 每次先删掉自己造的数据（order_sn 以 SLOWSQL 开头），
--       再重新灌到目标行数，不会污染原始业务数据。
--
-- 说明：MySQL 5.7 没有递归 CTE，用「数字表 + 笛卡尔积」生成序列，
--       这是 5.7 下的标准替代写法。
-- ============================================================

-- 0) 清掉上一次造的数据（原始 91 条业务数据的 order_sn 不是 SLOWSQL 开头，不受影响）
DELETE FROM oms_order WHERE order_sn LIKE 'SLOWSQL%';

-- 1) 造一张 0~9 的数字表
DROP TABLE IF EXISTS tmp_nums;
CREATE TABLE tmp_nums (d INT);
INSERT INTO tmp_nums VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

-- 2) 关掉自动提交：200 万行一次性提交，避免逐行 fsync 拖慢造数
SET autocommit = 0;

-- 3) 笛卡尔积 7 张数字表 = 0~9999999，取前 200 万条
INSERT INTO oms_order
    (member_id, order_sn, create_time, member_username,
     total_amount, pay_amount, status, order_type, source_type,
     receiver_name, receiver_phone, delete_status, note)
SELECT
    (n.num % 5000) + 1,                              -- member_id 1~5000：平均每人 400 单
    CONCAT('SLOWSQL', LPAD(n.num, 14, '0')),
    DATE_SUB(NOW(), INTERVAL (n.num % 730) DAY),     -- 时间散布在两年内
    CONCAT('member', (n.num % 5000) + 1),
    100 + (n.num % 9900),
    100 + (n.num % 9900),
    n.num % 6,                                       -- status 0~5
    0, 0,
    CONCAT('收货人', n.num % 1000),
    CONCAT('138', LPAD(n.num % 100000000, 8, '0')),
    0,
    CONCAT('压测订单-', n.num)
FROM (
    SELECT a.d + b.d*10 + c.d*100 + d.d*1000 + e.d*10000 + f.d*100000 + g.d*1000000 AS num
    FROM tmp_nums a
    CROSS JOIN tmp_nums b
    CROSS JOIN tmp_nums c
    CROSS JOIN tmp_nums d
    CROSS JOIN tmp_nums e
    CROSS JOIN tmp_nums f
    CROSS JOIN tmp_nums g
) n
WHERE n.num < 2000000;

COMMIT;
SET autocommit = 1;

DROP TABLE IF EXISTS tmp_nums;

-- 4) 核对结果
SELECT COUNT(*) AS total_orders,
       COUNT(DISTINCT member_id) AS distinct_members,
       SUM(order_sn LIKE 'SLOWSQL%') AS generated_rows
FROM oms_order;
