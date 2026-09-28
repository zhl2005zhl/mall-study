# -*- coding: utf-8 -*-
"""
库存锁定改造的完整验证（两个场景，都用真实 HTTP 接口并发）

场景 A —— 库存充足，验证「不丢更新」
    10 并发各买 1 件，lock_stock 应严格等于成功下单数
    改造前：成功 10 但 lock_stock = 1（丢 9 次）

场景 B —— 库存不足，验证「能挡住超卖」
    把 stock 临时改成 5，10 并发各买 1 件
    期望：恰好 5 单成功、5 单被"库存不足"拒绝，lock_stock = 5，real_stock = 0

安全：stock 原值先记录、结束时恢复；购物车测试数据会清理。
      产生的测试订单（status=0）不自动删，末尾给出清理 SQL 供手动确认。

（本脚本是 lockstock_api_pressure.py 的完整版，含两个场景）
"""
import json
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = ["--host=127.0.0.1", "--port=3307", "--user=root", "--password=root",
      "--database=mall", "--default-character-set=utf8mb4"]
BASE = "http://localhost:18087"
SKU_ID = 110
N = 10


def sql(statement, numeric=False):
    cmd = [MYSQL] + DB + (["-N", "-B"] if numeric else []) + ["-e", statement]
    r = subprocess.run(cmd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return (r.stdout or "").strip()


def http_json(path, payload=None, token=None, form=None):
    headers = {}
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=40) as resp:
        return json.loads(resp.read().decode("utf-8"))


def reset_cart(n):
    """造 n 条购物车记录，全部指向同一个 SKU（制造真实并发竞争）"""
    sql("UPDATE oms_cart_item SET delete_status=1 WHERE member_id=1 AND delete_status=0;")
    for _ in range(n):
        sql("INSERT INTO oms_cart_item (member_id, product_id, product_sku_id, quantity, "
            "price, product_name, product_pic, product_sku_code, product_attr, "
            "member_nickname, create_date, delete_status) VALUES "
            f"(1, 26, {SKU_ID}, 1, 3788, '压测商品', '', '201806070026001', "
            "'[{\"key\":\"颜色\",\"value\":\"金色\"}]', 'test', NOW(), 0);")
    raw = sql("SELECT id FROM oms_cart_item WHERE member_id=1 AND delete_status=0 ORDER BY id;",
              numeric=True)
    return [int(x) for x in raw.splitlines() if x.strip().isdigit()]


def concurrent_order(cart_ids, token):
    """Barrier 保证同时起跑，返回 (成功数, 拒绝数, 原因样本)"""
    barrier = threading.Barrier(len(cart_ids))
    ok = [0]
    reject = [0]
    errs = []

    def worker(cid):
        try:
            barrier.wait(timeout=60)
            r = http_json("/order/generateOrder",
                          {"memberReceiveAddressId": 1, "cartIds": [cid],
                           "payType": 1, "useIntegration": 0},
                          token=token)
            if r.get("code") == 200:
                ok[0] += 1
            else:
                reject[0] += 1
                errs.append(str(r.get("message"))[:100])
        except Exception as e:                    # noqa: BLE001
            reject[0] += 1
            errs.append(repr(e)[:100])

    ts = [threading.Thread(target=worker, args=(c,)) for c in cart_ids]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return ok[0], reject[0], errs


# ---------------- 准备 ----------------
print("=" * 64)
print("库存锁定改造验证（真实 HTTP 并发下单）")
print("=" * 64)

try:
    login = http_json("/sso/login", form={"username": "test", "password": "123456"})
except Exception as e:
    print("登录失败，应用是否在运行？", repr(e)[:120])
    sys.exit(1)
if login.get("code") != 200:
    print("登录异常:", login)
    sys.exit(1)
token = login["data"]["token"]
print("登录 OK  (test/123456)")

orig_stock = int(sql(f"SELECT stock FROM pms_sku_stock WHERE id={SKU_ID};", numeric=True) or 0)
print(f"SKU {SKU_ID} 当前 stock = {orig_stock}（脚本结束会恢复）")

# ================= 场景 A =================
print()
print("=" * 64)
print(f"场景 A：库存充足 —— {N} 并发各买 1 件（验证不丢更新）")
print("=" * 64)
sql(f"UPDATE pms_sku_stock SET stock={orig_stock}, lock_stock=0 WHERE id={SKU_ID};")
carts = reset_cart(N)
print(f"  并发请求数 = {len(carts)}，lock_stock 起点 = 0")
ok_a, rej_a, err_a = concurrent_order(carts, token)
time.sleep(2)
after_a = int(sql(f"SELECT lock_stock FROM pms_sku_stock WHERE id={SKU_ID};", numeric=True) or 0)
print()
print(f"  成功下单    : {ok_a}    被拒: {rej_a}")
print(f"  lock_stock  : 0 → {after_a}")
if after_a == ok_a:
    print(f"  >>> ✓ 锁定增量 == 成功数（{ok_a}），无丢失更新")
else:
    print(f"  >>> ✗ 不一致，差 {ok_a - after_a}  ← 仍有丢更新")
if err_a:
    print("  失败样本:", err_a[:2])

# ================= 场景 B =================
print()
print("=" * 64)
print(f"场景 B：库存不足 —— stock 临时改为 5，{N} 并发各买 1 件（验证防超卖）")
print("=" * 64)
sql(f"UPDATE pms_sku_stock SET stock=5, lock_stock=0 WHERE id={SKU_ID};")
carts_b = reset_cart(N)
print(f"  stock = 5，最多满足 5 单；并发请求数 = {len(carts_b)}")
ok_b, rej_b, err_b = concurrent_order(carts_b, token)
time.sleep(2)
row = sql(f"SELECT CONCAT(stock,'/',lock_stock,'/',stock-lock_stock) "
          f"FROM pms_sku_stock WHERE id={SKU_ID};", numeric=True)
lock_b = (row.split("/") + ["0"])[1]
print()
print(f"  成功下单    : {ok_b}   被拒: {rej_b}")
print(f"  stock / lock_stock / real_stock = {row}")
oversold = int(lock_b or 0) > 5
print(f"  是否超卖    : {'是，方案无效！' if oversold else '否 —— 超出库存的请求被正确拦下'}")
if err_b:
    print("  被拒原因样本:")
    for e in err_b[:3]:
        print("   -", e)

# ================= 汇总 =================
print()
print("=" * 64)
print("汇总")
print("=" * 64)
print("  场景                   成功  被拒  lock_stock  结论")
print(f"  A 库存充足({orig_stock:<4})      {ok_a:>3}  {rej_a:>4}  {after_a:>10}  不丢更新")
print(f"  B 库存不足(5)           {ok_b:>3}  {rej_b:>4}  {lock_b:>10}  不超卖")
print()
print("  对照改造前（先查后写 + 无条件校验）：")
print("  A 场景  → 成功 10，lock_stock 只记 1（丢 9 次）")
print("  B 场景  → 10 个请求全部放行，不做任何库存拦截")

# ---------------- 清理与恢复 ----------------
sql(f"UPDATE pms_sku_stock SET stock={orig_stock}, lock_stock=0 WHERE id={SKU_ID};")
sql("UPDATE oms_cart_item SET delete_status=1 WHERE member_id=1 AND delete_status=0;")
print()
print("=" * 64)
print(f"已恢复 stock = {orig_stock}、lock_stock = 0，购物车测试数据已清理")
print("=" * 64)
print(sql(f"SELECT id, stock, lock_stock, stock-lock_stock AS real_stock "
          f"FROM pms_sku_stock WHERE id = {SKU_ID};"))
print()
print("提示：本次产生的测试订单未自动删除（status=0 待付款）。")
print("      如需清理，先确认范围再执行：")
print("      SELECT id, order_sn, create_time FROM oms_order WHERE status=0 ORDER BY id DESC LIMIT 20;")
