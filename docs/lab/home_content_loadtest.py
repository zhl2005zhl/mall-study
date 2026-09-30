#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
首页接口压测脚本（零依赖，只用标准库）

用法：
    python home_content_loadtest.py [URL] [进程数] [每进程线程数] [持续秒数]
默认：
    URL            = http://localhost:18087/home/content
    进程数         = 4
    每进程线程数   = 10
    持续秒数       = 15
    → 实际并发 = 4 × 10 = 40

为什么用「多进程 + 多线程」两层结构：
  1. Python 有 GIL，单进程内开再多线程也无法真正并行跑 Python 字节码。
     只用多线程时，压测客户端自己会先撞到天花板，
     测出来的是「客户端能力」而不是「服务端能力」。
  2. 线程负责 I/O（等响应），进程负责绕开 GIL，这样才压得动服务端。
  3. 每个线程持有一条 keep-alive 长连接反复请求。
     绝不能用 urllib.urlopen —— 它每次请求新建 TCP 连接，
     几千次请求会把 Windows 临时端口耗尽，产生大量假的连接错误，
     把「服务端性能」测成「客户端端口不够用」。

输出：成功请求数、QPS、错误分类、平均/中位/P95/P99/最大 延迟
"""

import http.client
import json
import multiprocessing as mp
import sys
import time
from urllib.parse import urlparse

URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18087/home/content"
PROCESSES = int(sys.argv[2]) if len(sys.argv) > 2 else 4
THREADS = int(sys.argv[3]) if len(sys.argv) > 3 else 10
DURATION = float(sys.argv[4]) if len(sys.argv) > 4 else 15.0

_parsed = urlparse(URL)
HOST = _parsed.hostname
PORT = _parsed.port or 80
PATH = _parsed.path or "/"
if _parsed.query:
    PATH = PATH + "?" + _parsed.query


def _thread_loop(deadline, out_lat, out_err):
    """单个压测线程：持一条长连接，在 deadline 前不停发请求"""
    import threading  # noqa: F401  (局部导入，兼容 spawn 模式)

    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    while time.time() < deadline:
        t0 = time.perf_counter()
        try:
            conn.request("GET", PATH)
            resp = conn.getresponse()
            resp.read()
            if resp.status == 200:
                out_lat.append((time.perf_counter() - t0) * 1000.0)
            else:
                k = "HTTP_%d" % resp.status
                out_err[k] = out_err.get(k, 0) + 1
        except Exception as e:
            k = type(e).__name__
            out_err[k] = out_err.get(k, 0) + 1
            try:
                conn.close()
            except Exception:
                pass
            conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    try:
        conn.close()
    except Exception:
        pass


def _process_worker(args):
    """一个压测进程：内部开 THREADS 个线程"""
    deadline = args
    lat = []
    err = {}
    import threading

    threads = []
    for _ in range(THREADS):
        t = threading.Thread(target=_thread_loop, args=(deadline, lat, err))
        t.start()
        threads.append(t)
    for t in threads:
        t.join()
    return lat, err


def percentile(sorted_values, p):
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(sorted_values) - 1)
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (k - lo)


def main():
    concurrency = PROCESSES * THREADS
    print("目标     : %s" % URL)
    print("并发     : %d 进程 × %d 线程 = %d 并发   持续 %.0f 秒"
          % (PROCESSES, THREADS, concurrency, DURATION))
    print("-" * 56)

    start = time.time()
    deadline = start + DURATION
    with mp.Pool(processes=PROCESSES) as pool:
        results = pool.map(_process_worker, [deadline] * PROCESSES)
    elapsed = time.time() - start

    all_lat = []
    error_types = {}
    for lat, errs in results:
        all_lat.extend(lat)
        for k, v in errs.items():
            error_types[k] = error_types.get(k, 0) + v

    if not all_lat:
        print("没有成功的请求。错误分类：%s" % error_types)
        return

    all_lat.sort()
    total = len(all_lat)
    summary = {
        "url": URL,
        "concurrency": concurrency,
        "duration_sec": round(elapsed, 2),
        "success_requests": total,
        "qps": round(total / elapsed, 1),
        "error_total": sum(error_types.values()),
        "error_types": error_types,
        "latency_ms": {
            "avg": round(sum(all_lat) / total, 2),
            "p50": round(percentile(all_lat, 0.50), 2),
            "p95": round(percentile(all_lat, 0.95), 2),
            "p99": round(percentile(all_lat, 0.99), 2),
            "max": round(all_lat[-1], 2),
        },
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return summary


if __name__ == "__main__":
    main()
