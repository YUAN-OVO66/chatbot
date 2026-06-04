#!/usr/bin/env python3
"""获取资源占用最高的进程"""
import sys

top_n = int(sys.argv[1]) if len(sys.argv) > 1 else 5

try:
    import psutil
    print(f"=== CPU 占用 Top {top_n} ===")
    procs = []
    for p in psutil.process_iter(['pid', 'name', 'cpu_percent', 'memory_percent']):
        try:
            procs.append(p.info)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    procs.sort(key=lambda x: x.get('cpu_percent', 0) or 0, reverse=True)
    for i, p in enumerate(procs[:top_n]):
        cpu = p.get('cpu_percent', 0) or 0
        mem = p.get('memory_percent', 0) or 0
        print(f"  {i+1}. PID={p['pid']:>6}  CPU={cpu:>5.1f}%  MEM={mem:>5.1f}%  {p['name']}")

    print(f"\n=== 内存占用 Top {top_n} ===")
    procs.sort(key=lambda x: x.get('memory_percent', 0) or 0, reverse=True)
    for i, p in enumerate(procs[:top_n]):
        cpu = p.get('cpu_percent', 0) or 0
        mem = p.get('memory_percent', 0) or 0
        print(f"  {i+1}. PID={p['pid']:>6}  CPU={cpu:>5.1f}%  MEM={mem:>5.1f}%  {p['name']}")
except ImportError:
    print("[提示] psutil 未安装，无法获取进程信息")
    print("请运行: pip install psutil")
