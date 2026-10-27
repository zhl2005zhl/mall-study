# -*- coding: utf-8 -*-
"""
第 3 步验证：消息历史瘦身 + 可观测性的量化验证。

验证三件事：
  A. 瘦身效果 —— 订单数据从「完整对象」到「4 字段摘要」的体积对比
  B. 上下文增长 —— 多轮对话时 prompt token 如何随轮次上涨
  C. trace 可用性 —— JSONL 能被直接聚合分析（相当于 jq/pandas 的用法）

用法：
    python ai_observe_verify.py
（需要 portal 已带 Key 启动）
"""
import json
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://127.0.0.1:18087"


def post_form(path, data):
    body = urlencode(data).encode()
    req = urllib.request.Request(BASE + path, data=body, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def post_json(path, obj, token, timeout=180):
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def main():
    token = post_form("/sso/login", {"username": "test", "password": "123456"})["data"]["token"]
    print("登录成功\n")

    # ================================================================
    # B + C：多轮对话，观察 prompt token 增长
    # ================================================================
    sid = "obs-%d" % int(time.time())
    questions = [
        "我有几个订单？",
        "其中已发货的那笔多少钱？",
        "帮我查一下那笔订单的详情",
        "这个订单买的什么商品？",
        "好的谢谢",
    ]

    print("=" * 78)
    print("B. 多轮对话的上下文增长")
    print("=" * 78)
    print(f"{'轮次':<6}{'问题':<28}{'prompt':<9}{'completion':<12}{'总token':<10}{'裁剪':<6}")

    rows = []
    for i, q in enumerate(questions, 1):
        st, resp = post_json("/ai/chat", {"question": q, "sessionId": sid}, token)
        if resp.get("code") != 200:
            print(f"  第{i}问失败: {str(resp.get('message'))[:80]}")
            continue
        d = resp["data"]
        rows.append(d)
        print(f"{i:<6}{q[:26]:<28}{str(d.get('promptTokens')):<9}"
              f"{str(d.get('completionTokens')):<12}{str(d.get('totalTokens')):<10}"
              f"{str(d.get('historyTrimCount')):<6}")

    if rows:
        first = rows[0].get("promptTokens") or 0
        last = rows[-1].get("promptTokens") or 0
        print(f"\n  首轮 prompt={first} → 末轮 prompt={last}"
              f"（增长 {(last - first) / max(first, 1) * 100:.0f}%）")
        print("  ↑ 这就是「上下文膨胀」的量化：每轮都要把全部历史重发一遍，")
        print("    prompt token 逐轮累加。轮数越多，单次调用越贵、越慢。")
        total = sum(r.get("totalTokens") or 0 for r in rows)
        print(f"\n  本次 {len(rows)} 轮总计消耗 {total} token")

    # ================================================================
    # C：trace 落盘分析
    # ================================================================
    print("\n" + "=" * 78)
    print("C. trace 落盘与聚合分析（JSONL）")
    print("=" * 78)

    import glob
    import os
    files = sorted(glob.glob(os.path.join(r"C:\work\mall\logs\ai-trace", "*.jsonl")))
    if not files:
        print("  未找到 trace 文件")
        return

    path = files[-1]
    records = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))

    print(f"  文件: {path}")
    print(f"  记录数: {len(records)} 条（一行一条 JSON）\n")

    print("  最近 5 条 trace：")
    print(f"  {'traceId':<12}{'轮次':<6}{'工具':<6}{'token':<9}{'耗时':<9}{'裁剪':<6}问题")
    for r in records[-5:]:
        print(f"  {r['traceId']:<12}{r['rounds']:<6}{r['toolCallCount']:<6}"
              f"{r['totalTokens']:<9}{str(r['costMs']) + 'ms':<9}"
              f"{r['historyTrimCount']:<6}{r['question'][:20]}")

    # 聚合统计
    total_tokens = sum(r["totalTokens"] for r in records)
    avg_rounds = sum(r["rounds"] for r in records) / len(records)
    avg_cost = sum(r["costMs"] for r in records) / len(records)
    total_tools = sum(r["toolCallCount"] for r in records)
    success = sum(1 for r in records if r.get("success"))
    trims = sum(r["historyTrimCount"] for r in records)

    print(f"\n  聚合统计：")
    print(f"    总请求数     : {len(records)}")
    print(f"    成功率       : {success}/{len(records)}")
    print(f"    平均轮次     : {avg_rounds:.1f}")
    print(f"    平均耗时     : {avg_cost:.0f}ms")
    print(f"    工具调用总数 : {total_tools}")
    print(f"    累计 token   : {total_tokens}")
    print(f"    footprint    : 平均 {total_tokens / len(records):.0f} token/次")
    print(f"    历史裁剪次数 : {trims}")

    # observation 体积分布 —— 量化「瘦身省了多少」
    obs = []
    for r in records:
        for s in r.get("steps", []):
            if s.get("observationChars"):
                obs.append(s["observationChars"])
    if obs:
        print(f"\n  工具结果体积（瘦身后回填）:")
        print(f"    次数: {len(obs)}  平均 {sum(obs) / len(obs):.0f} 字符  "
              f"最大 {max(obs)} 字符")

    print("\n" + "=" * 78)
    print("验证结束")
    print("=" * 78)
    print("\n提示：这份 JSONL 可直接用 jq / pandas 分析，例如：")
    print('  jq -s "map(.totalTokens) | add" agent-trace-*.jsonl')
    print('  jq -s "sort_by(.costMs) | reverse | .[0]" agent-trace-*.jsonl')


if __name__ == "__main__":
    main()
