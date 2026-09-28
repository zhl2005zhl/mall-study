# -*- coding: utf-8 -*-
"""
改造后验证：用真实 HTTP 接口并发下单，验证库存锁定是否准确

对照点：
  改造前（先查后写）   -> 10 并发下单，lock_stock 只记 1（丢 9 次）
  改造后（原子 SQL）   -> lock_stock 应严格等于成功下单数

做法：
  1. 登录会员 test 拿 token
  2. 直接造 N 条购物车记录，全部指向同一个 SKU（制造真实并发竞争）
  3. 用 Barrier 让 N 个请求同时打 /order/generateOrder（每个用不同 cartId）
  4. 比对：lock_stock 增量 == 成功下单数

安全：购物车记录是自己造的，跑完清理；不改任何业务配置。
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


print("=" * 62)
print("改造后验证：HTTP 接口并发下单")
print("=" * 62)

# ---------- 1. 登录 ----------
try:
    login = http_json("/sso/login", form={"username": "test", "password": "123456"})
except Exception as e:
    print("登录失败，应用是否在运行？", repr(e)[:120])
    sys.exit(1)
if login.get("code") != 200:
    print("登录返回异常:", login)
    sys.exit(1)
token = login["data"]["token"]
print("登录 OK  (test/123456)")

# ---------- 2. 造购物车记录 ----------
sql("UPDATE oms_cart_item SET delete_status=1 WHERE member_id=1 AND delete_status=0;")
for _ in range(N):
    sql("INSERT INTO oms_cart_item (member_id, product_id, product_sku_id, quantity, "
        "price, product_name, product_pic, product_sku_code, product_attr, "
        "member_nickname, create_date, delete_status) VALUES "
        f"(1, 26, {SKU_ID}, 1, 3788, '压测商品', '', '201806070026001', "
        "'[{\"key\":\"颜色\",\"value\":\"金色\"}]', 'test', NOW(), 0);")

raw = sql("SELECT id FROM oms_cart_item WHERE member_id=1 AND delete_status=0 ORDER BY id;",
          numeric=True)
cart_ids = [int(x) for x in raw.splitlines() if x.strip().isdigit()]
print(f"已构造 {len(cart_ids)} 条购物车记录（全部指向 SKU {SKU_ID}）")

if len(cart_ids) < N:
    print(f"注意：只造出 {len(cart_ids)} 条，按实际数量并发")

# ---------- 3. 并发下单 ----------
before = int(sql(f"SELECT lock_stock FROM pms_sku_stock WHERE id={SKU_ID};", numeric=True) or 0)
print(f"\n下单前 lock_stock = {before}")
print(f"准备 {len(cart_ids)} 个请求同时发起...\n")

barrier = threading.Barrier(len(cart_ids))
ok = [0]
fail = [0]
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
            fail[0] += 1
            errs.append(str(r.get("message"))[:110])
    except Exception as e:                       # noqa: BLE001
        fail[0] += 1
        errs.append(repr(e)[:110])


ts = [threading.Thread(target=worker, args=(c,)) for c in cart_ids]
for t in ts:
    t.start()
for t in ts:
    t.join()

time.sleep(2)
after = int(sql(f"SELECT lock_stock FROM pms_sku_stock WHERE id={SKU_ID};", numeric=True) or 0)
delta = after - before

# ---------- 4. 结论 ----------
print("=" * 62)
print("结果")
print("=" * 62)
print(f"  成功下单        : {ok[0]}")
print(f"  失败            : {fail[0]}")
print(f"  lock_stock      : {before} → {after}   增量 = {delta}")
print()
if delta == ok[0]:
    print(f"  >>> ✓ 锁定增量 == 成功下单数（{ok[0]}），无丢失更新")
else:
    print(f"  >>> ✗ 不一致：锁定增量 {delta}，成功 {ok[0]}，"
          f"差 {ok[0] - delta}  ← 说明仍有丢更新")
if errs:
    print("\n  失败样本：")
    for e in errs[:4]:
        print("   -", e)

print()
print("=" * 62)
print("对照：改造前同样场景下，lock_stock 只会记 1（成功 10）")
print("=" * 62)

# ---------- 5. 清理 ----------
sql("UPDATE oms_cart_item SET delete_status=1 WHERE member_id=1 AND delete_status=0;")
print("\n购物车测试数据已清理。SKU 库存状态：")
print(sql(f"SELECT id, stock, lock_stock, stock - lock_stock AS real_stock "
          f"FROM pms_sku_stock WHERE id = {SKU_ID};"))
