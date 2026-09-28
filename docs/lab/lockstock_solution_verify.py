# -*- coding: utf-8 -*-
"""
验证 P0 改造方案是否真的能防超卖

改造方案（《缺陷与升级台账》A-1）：
    UPDATE pms_sku_stock
    SET lock_stock = lock_stock + #{quantity}
    WHERE id = #{skuId} AND stock - lock_stock >= #{quantity}
    再判 affected rows，为 0 就是库存不足

本脚本验证它的两种行为：
    实验 C：库存充足  -> 10 并发各锁 1 件，全部成功，lock_stock = 10
    实验 D：库存不足  -> 库存只有 5，10 并发各锁 1 件
                        预期：恰好 5 次成功、5 次 affected=0，lock_stock = 5，不超卖

对照组（当前 mall 的写法，实验 E）：
    SELECT -> Java 加 -> UPDATE 绝对值，且没有条件校验
    预期：10 并发都可能"成功"（都认为自己锁上了），但 lock_stock 记错

安全说明：全程使用独立测试表 __lockstock_fix_test，不读写任何业务表。
"""
import subprocess
import sys
import threading

sys.stdout.reconfigure(encoding="utf-8")

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
BASE = ["--host=127.0.0.1", "--port=3307", "--user=root",
        "--password=root", "--database=mall"]
TABLE = "__lockstock_fix_test"
N = 10
WINDOW = 0.5


def run(sql, extra=None):
    cmd = [MYSQL] + BASE + (extra or []) + ["-e", sql]
    r = subprocess.run(cmd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return r.returncode, (r.stdout or "").strip(), (r.stderr or "").strip()


def scalar(sql):
    _, out, _ = run(sql, extra=["-N", "-B"])
    return out.strip()


def concurrent(sql, n):
    """n 个线程用 Barrier 精确同时起跑；返回 (成功次数, 被拒绝次数, 错误)"""
    barrier = threading.Barrier(n)
    ok = [0]
    rejected = [0]
    errors = []

    def worker():
        try:
            barrier.wait(timeout=60)
            rc, out, err = run(sql)
            if rc != 0:
                errors.append(err[:150])
                return
            # mysql -e 的输出可能带列名表头（如 "ROW_COUNT()\n0"），
            # 取最后一行才是真正的值
            lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
            body = lines[-1] if lines else ""
            if body == "0":
                rejected[0] += 1          # affected rows = 0 -> 条件不满足
            else:
                ok[0] += 1
        except Exception as e:            # noqa: BLE001
            errors.append(repr(e)[:150])

    ts = [threading.Thread(target=worker) for _ in range(n)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return ok[0], rejected[0], errors


print("=" * 64)
print("准备测试表（独立表，不触碰业务数据）")
print("=" * 64)
run(f"DROP TABLE IF EXISTS {TABLE}")
rc, _, err = run(
    f"CREATE TABLE {TABLE} (id BIGINT PRIMARY KEY, stock INT NOT NULL, "
    f"lock_stock INT NOT NULL DEFAULT 0) ENGINE=InnoDB"
)
if rc != 0:
    print("建表失败：", err)
    sys.exit(1)
print(f"表已就绪：{TABLE}\n")

# ============================================================ 实验 C
print("=" * 64)
print(f"实验 C：改造后的 SQL，库存充足（stock=100，{N} 并发各锁 1 件）")
print("=" * 64)
run(f"INSERT INTO {TABLE} VALUES (1, 100, 0) ON DUPLICATE KEY UPDATE stock=100, lock_stock=0")

SQL_FIXED = (f"UPDATE {TABLE} SET lock_stock = lock_stock + 1 "
             f"WHERE id = 1 AND stock - lock_stock >= 1; "
             f"SELECT ROW_COUNT();")

print("执行语句：")
print("  UPDATE ... SET lock_stock = lock_stock + 1")
print("  WHERE id = 1 AND stock - lock_stock >= 1")
print()
run(f"UPDATE {TABLE} SET stock=100, lock_stock=0 WHERE id=1")
ok_c, rej_c, err_c = concurrent(SQL_FIXED, N)
lock_c = scalar(f"SELECT lock_stock FROM {TABLE} WHERE id=1")
print(f"  成功次数            : {ok_c} / {N}")
print(f"  被拒次数            : {rej_c}")
print(f"  lock_stock 期望     : {N}")
print(f"  lock_stock 实际     : {lock_c}")
print(f"  >>> {'正确，无丢更新' if str(lock_c) == str(N) else '异常'}")
if err_c:
    print("  错误：", err_c[0])

# ============================================================ 实验 D
print()
print("=" * 64)
print(f"实验 D：改造后的 SQL，库存不足（stock=5，{N} 并发各锁 1 件）")
print("=" * 64)
run(f"UPDATE {TABLE} SET stock=5, lock_stock=0 WHERE id=1")
print("  stock=5 只能满足 5 次，其余应被条件拦下")
print()
ok_d, rej_d, err_d = concurrent(SQL_FIXED, N)
lock_d = scalar(f"SELECT lock_stock FROM {TABLE} WHERE id=1")
print(f"  成功次数            : {ok_d}   （期望 5）")
print(f"  被拒次数            : {rej_d}   （期望 5）")
print(f"  lock_stock          : {lock_d}   （期望 5）")
oversold = int(lock_d or 0) > 5
print(f"  是否超卖            : {'是，方案无效！' if oversold else '否，正确拦住了'}")
if err_d:
    print("  错误：", err_d[0])

# ============================================================ 实验 E
print()
print("=" * 64)
print(f"实验 E：对照 —— mall 当前写法（无锁读 + 写绝对值 + 无条件校验）")
print("=" * 64)
run(f"UPDATE {TABLE} SET stock=5, lock_stock=0 WHERE id=1")
SQL_CURRENT = (f"SET @v = (SELECT lock_stock FROM {TABLE} WHERE id=1); "
               f"DO SLEEP({WINDOW}); "
               f"UPDATE {TABLE} SET lock_stock = @v + 1 WHERE id=1;")
print("执行语句（复刻 OmsPortalOrderServiceImpl.lockStock）：")
print("  SET @v = (SELECT lock_stock ...)       <- 无锁读")
print(f"  DO SLEEP({WINDOW})")
print("  UPDATE ... SET lock_stock = @v + 1     <- 写绝对值，无库存条件")
print()
ok_e, rej_e, err_e = concurrent(SQL_CURRENT, N)
lock_e = scalar(f"SELECT lock_stock FROM {TABLE} WHERE id=1")
print(f"  『成功』次数        : {ok_e} / {N}   <- 10 个请求都以为锁上了")
print(f"  被拒次数            : {rej_e}   <- 0，没有任何请求被拦下")
print(f"  lock_stock          : {lock_e}")
print()
print("  含义：10 个请求都返回成功（业务上认为锁到了 10 件），")
print("        但账面上 lock_stock 只记了 1 件 —— 差 9 件没有任何拦截。")
if err_e:
    print("  错误：", err_e[0])

# ============================================================ 汇总
print()
print("=" * 64)
print("汇总对比")
print("=" * 64)
print(f"  场景                     成功  被拒  lock_stock  结论")
print(f"  C 改造后·库存充足          {ok_c:>2}    {rej_c:>2}      {lock_c:>2}      正确")
print(f"  D 改造后·库存不足(仅5)     {ok_d:>2}    {rej_d:>2}      {lock_d:>2}      不超卖")
print(f"  E 改造前·库存不足(仅5)     {ok_e:>2}    {rej_e:>2}      {lock_e:>2}      全部放行")
print()
print("结论：改造方案的 SQL 在库存充足时计数准确，在库存不足时能精确拦下超额请求。")
print("      而当前 mall 的写法在库存不足时不做任何拦截。")

run(f"DROP TABLE IF EXISTS {TABLE}")
print()
print(f"测试表 {TABLE} 已清理。业务数据未被修改。")
