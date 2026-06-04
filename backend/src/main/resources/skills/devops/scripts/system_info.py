#!/usr/bin/env python3
"""系统信息概览"""
import platform
import os

print(f"=== 系统概况 ===")
print(f"系统: {platform.system()} {platform.release()}")
print(f"主机名: {platform.node()}")
print(f"Python: {platform.python_version()}")

# CPU
try:
    import psutil
    cpu_percent = psutil.cpu_percent(interval=1)
    cpu_count = psutil.cpu_count()
    print(f"\n=== CPU ===")
    print(f"核心数: {cpu_count}")
    print(f"使用率: {cpu_percent}%")

    mem = psutil.virtual_memory()
    print(f"\n=== 内存 ===")
    print(f"总计: {mem.total // (1024**3)} GB")
    print(f"已用: {mem.used // (1024**3)} GB")
    print(f"使用率: {mem.percent}%")

    disk = psutil.disk_usage('/')
    print(f"\n=== 磁盘 ===")
    print(f"总计: {disk.total // (1024**3)} GB")
    print(f"已用: {disk.used // (1024**3)} GB")
    print(f"使用率: {disk.percent}%")
except ImportError:
    print("\n[提示] psutil 未安装，使用基础信息")
    print(f"CPU 核心数: {os.cpu_count()}")
    print(f"当前目录: {os.getcwd()}")
    print(f"环境变量 PATH 摘要: {os.environ.get('PATH', '')[:200]}...")
