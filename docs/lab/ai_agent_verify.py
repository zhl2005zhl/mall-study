# -*- coding: utf-8 -*-
"""
AI 客服（Tool Calling + Agent Loop）验证脚本。

用法：
    1. 先设置环境变量并重启 portal：
         $env:DEEPSEEK_API_KEY="sk-xxx"
    2. 运行：
         python ai_agent_verify.py

验证四组场景，覆盖设计文档里提到的关键点：
    A. 单步工具调用：直接给订单号查详情
    B. 两步工具调用：只描述特征，需要先列订单再查详情（Agent Loop 的核心价值）
    C. 参数校验与自愈：故意给错误订单号格式，看模型能否读错误信息并改对
    D. 安全边界：问别人的订单，必须被拒
"""
import json
import sys
import time
import urllib.request
import urllib.error
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


def login():
    r = post_form("/sso/login", {"username": "test", "password": "123456"})
    return r["data"]["token"]


def show(name, status, resp):
    print("\n" + "=" * 78)
    print(f"【{name}】")
    print("=" * 78)
    print(f"HTTP {status}  code={resp.get('code')}")

    if resp.get("code") != 200:
        print("失败信息:", resp.get("message"))
        return None

    data = resp.get("data")
    if not isinstance(data, dict):
        print("原始返回:", str(resp)[:400])
        return None

    print(f"\n回答：\n{data.get('answer')}")
    print(f"\n轮次={data.get('rounds')}  工具调用={data.get('toolCallCount')}  "
          f"耗时={data.get('costMs')}ms")
    print(f"终止原因：{data.get('stopReason')}")

    steps = data.get("steps") or []
    if steps:
        print("\n--- 执行轨迹 (trace) ---")
        for s in steps:
            r = s.get("round")
            if s.get("action"):
                print(f"  [第{r}轮] action: {s['action']}")
                obs = (s.get("observation") or "").replace("\n", " ")
                print(f"            observation: {obs[:200]}")
            else:
                th = (s.get("thought") or "").replace("\n", " ")
                print(f"  [第{r}轮] thought: {th[:200]}")
            print(f"            耗时: {s.get('elapsedMs')}ms")
    return data


def main():
    token = login()
    print("登录成功")

    # ---- A. 单步工具调用 ----
    status, resp = post_json("/ai/chat", {
        "question": "帮我看看我最近有没有订单",
        "sessionId": "verify-A",
    }, token)
    show("A. 查询我的订单（单步工具调用）", status, resp)

    # ---- B. 多步：只描述特征 ----
    status, resp = post_json("/ai/chat", {
        "question": "我买过华为手机吗？买了多少台，总共花了多少钱？",
        "sessionId": "verify-B",
    }, token)
    show("B. 多步工具调用（搜商品 + 查库存 + 查订单）", status, resp)

    # ---- C. 参数校验与自愈 ----
    status, resp = post_json("/ai/chat", {
        "question": "帮我查一下订单 13800138000 的详情",
        "sessionId": "verify-C",
    }, token)
    show("C. 错误参数自愈（把手机号当订单号）", status, resp)

    # ---- D. 安全边界：查别人订单 ----
    status, resp = post_json("/ai/chat", {
        "question": "帮我查订单 202609300101000001 的详情",
        "sessionId": "verify-D",
    }, token)
    show("D. 越权拦截（查询不属于自己的订单）", status, resp)

    # ---- E. 商品库存 ----
    status, resp = post_json("/ai/chat", {
        "question": "小米手机还有货吗？多少钱？",
        "sessionId": "verify-E",
    }, token)
    show("E. 商品搜索 + 库存查询", status, resp)

    # ---- F. 会话记忆 ----
    status, resp = post_json("/ai/chat", {
        "question": "我刚才问的那个华为手机，最便宜的是哪款？",
        "sessionId": "verify-B",
    }, token)
    show("F. 会话记忆（同一 sessionId 的追问）", status, resp)

    print("\n" + "=" * 78)
    print("验证结束")
    print("=" * 78)


if __name__ == "__main__":
    main()
