# -*- coding: utf-8 -*-
"""
验证 mall 下单锁库存 lockStock 的并发安全问题

原理：
  OmsPortalOrderServiceImpl.lockStock (734-740) 的实现是
      SELECT lock_stock  ->  Java 内存 +qty  ->  UPDATE 写绝对值
  这是 read-modify-write，并发下会丢失更新。

  本脚本用「多个独立 MySQL 连接 + Barrier 精确同时起跑」复刻该模式，
  并与「单条原子 SQL」对比，验证最终 lock_stock 是否等于实际成功次数。

安全说明：
  全程使用独立测试表 __lockstock_test，不读写任何业务表。
"""
import subprocess
import sys
import threading

sys.stdout.reconfigure(encoding="utf-8")

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
BASE = ["--host=127.0.0.1", "--port=3307", "--user=root",
        "--password=root", "--database=mall"]
N = 10           # 并发数
WINDOW = 0.5     # 放大 SELECT 与 UPDATE 之间的时间窗（秒）
TABLE = "__lockstock_test"


def run(sql, extra=None):
    """执行 SQL，返回 (返回码, stdout, stderr)"""
    cmd = [MYSQL] + BASE + (extra or []) + ["-e", sql]
    r = subprocess.run(cmd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return r.returncode, (r.stdout or "").strip(), (r.stderr or "").strip()


def scalar(sql):
    """取单个标量值"""
    _, out, _ = run(sql, extra=["-N", "-B"])
    return out.strip()


def concurrent(sql, n, label):
    """n 个线程用 Barrier 精确同时起跑，各执行一次 sql"""
    barrier = threading.Barrier(n)
    errors = []
    ok = [0]

    def worker():
        try:
            barrier.wait(timeout=60)          # 所有人到齐才一起冲
            rc, _, err = run(sql)
            if rc == 0:
                ok[0] += 1
            else:
                errors.append(err[:160])
        except Exception as e:                # noqa: BLE001
            errors.append(repr(e)[:160])

    threads = [threading.Thread(target=worker) for _ in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return ok[0], errors


def reset():
    run(f"UPDATE {TABLE} SET lock_stock = 0 WHERE id = 1")


def value():
    return scalar(f"SELECT lock_stock FROM {TABLE} WHERE id = 1")


print("=" * 62)
print("准备测试表（独立表，不触碰业务数据）")
print("=" * 62)
run(f"DROP TABLE IF EXISTS {TABLE}")
rc, _, err = run(
    f"CREATE TABLE {TABLE} (id BIGINT PRIMARY KEY, lock_stock INT NOT NULL "
    f"DEFAULT 0) ENGINE=InnoDB"
)
if rc != 0:
    print("建表失败，终止：", err)
    sys.exit(1)
run(f"INSERT INTO {TABLE} (id, lock_stock) VALUES (1, 0)")
print(f"表已就绪：{TABLE}（id=1, lock_stock=0）\n")

# ---------------------------------------------------------------- 实验 A
print("=" * 62)
print(f"实验 A：复刻 lockStock 的 read-modify-write（{N} 并发，各 1 件）")
print("=" * 62)
sql_rmw = (
    f"SET @v = (SELECT lock_stock FROM {TABLE} WHERE id=1); "
    f"DO SLEEP({WINDOW}); "
    f"UPDATE {TABLE} SET lock_stock = @v + 1 WHERE id=1;"
)
print("每个连接的执行序列：")
print("  SET @v = (SELECT lock_stock ...)   <- 读，不加锁")
print(f"  DO SLEEP({WINDOW})                   <- 对应 Java 侧代码执行耗时")
print("  UPDATE ... SET lock_stock = @v + 1 <- 写回绝对值")
print()

reset()
ok_a, err_a = concurrent(sql_rmw, N, "A")
final_a = value()
print(f"  成功执行连接数        : {ok_a} / {N}")
print(f"  期望 lock_stock      : {N}")
print(f"  实际 lock_stock      : {final_a}")
lost = N - int(final_a or 0)
if lost > 0:
    print(f"  >>> 丢失更新 {lost} 次，锁库存被少计了 {lost} 件")
else:
    print("  >>> 未观察到丢失（并发窗口可能未重叠，可增大 WINDOW 重试）")
if err_a:
    print("  错误样本：", err_a[0])

# ---------------------------------------------------------------- 实验 B
print()
print("=" * 62)
print(f"实验 B：改为原子 SQL（{N} 并发，各 1 件）")
print("=" * 62)
sql_atomic = f"UPDATE {TABLE} SET lock_stock = lock_stock + 1 WHERE id=1;"
print(f"每个连接的执行序列：")
print("  UPDATE ... SET lock_stock = lock_stock + 1 <- 数据库内原子运算")
print()

reset()
ok_b, err_b = concurrent(sql_atomic, N, "B")
final_b = value()
print(f"  成功执行连接数        : {ok_b} / {N}")
print(f"  期望 lock_stock      : {N}")
print(f"  实际 lock_stock      : {final_b}")
print(f"  >>> {'与期望一致，无丢失' if str(final_b) == str(N) else '仍不一致'}")
if err_b:
    print("  错误样本：", err_b[0])

# ---------------------------------------------------------------- 汇总
print()
print("=" * 62)
print("汇总")
print("=" * 62)
print(f"  A  read-modify-write  : lock_stock = {final_a}   （期望 {N}）")
print(f"  B  原子 SQL           : lock_stock = {final_b}   （期望 {N}）")
print()
print("对照 mall 源码：")
print("  A 对应  OmsPortalOrderServiceImpl.lockStock()  734-740 行")
print("  B 对应  PortalOrderDao.xml updateSkuStock()    50-67 行（项目里已有的写法）")

# ---------------------------------------------------------------- 清理
run(f"DROP TABLE IF EXISTS {TABLE}")
print()
print(f"测试表 {TABLE} 已清理。业务数据未被修改。")
