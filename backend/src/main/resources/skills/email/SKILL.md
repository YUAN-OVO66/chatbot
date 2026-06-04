---
name: email
description: 代表用户发送电子邮件。当用户想要写邮件、发邮件时使用此技能。
---

# 邮件发送技能

## 流程

1. 收集收件人邮箱、邮件主题、邮件正文
2. 确认信息后调用脚本发送

## 可用脚本

- `scripts/send_email.py` — 发送邮件
  - 执行方式: `python C:/tmp/skills/email/scripts/send_email.py --to <邮箱> --subject <主题> --body <内容>`
  - 返回: "邮件已发送" 或错误信息

## 注意事项

- 用户说"取消"时终止流程
- 发送前必须向用户确认所有信息
- 使用 shell 工具执行脚本，使用绝对路径
