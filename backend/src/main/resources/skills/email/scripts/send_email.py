#!/usr/bin/env python3
"""发送邮件脚本"""
import sys
import argparse

parser = argparse.ArgumentParser(description="发送邮件")
parser.add_argument("--to", required=True, help="收件人邮箱")
parser.add_argument("--subject", required=True, help="邮件主题")
parser.add_argument("--body", required=True, help="邮件正文")
args = parser.parse_args()

# 模拟邮件发送（实际使用时替换为真实 SMTP 调用）
print(f"邮件已发送给 {args.to}")
print(f"主题: {args.subject}")
print(f"状态: success")
