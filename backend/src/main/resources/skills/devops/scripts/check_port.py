#!/usr/bin/env python3
"""检查端口是否开放"""
import sys
import socket
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--host", required=True, help="目标主机")
parser.add_argument("--port", required=True, type=int, help="目标端口")
parser.add_argument("--timeout", type=int, default=3, help="超时秒数")
args = parser.parse_args()

try:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(args.timeout)
    result = sock.connect_ex((args.host, args.port))
    sock.close()
    if result == 0:
        print(f"端口 {args.host}:{args.port} 状态: 开放")
    else:
        print(f"端口 {args.host}:{args.port} 状态: 关闭 (error={result})")
except Exception as e:
    print(f"检测失败: {e}")
