---
name: translator
description: 翻译助手。当用户需要中英互译、多语言翻译、术语查询、翻译校对时使用此技能。
---

# 翻译助手技能

## 流程

1. 确认源语言和目标语言
2. 执行翻译任务
3. 提供翻译建议和用法说明

## 可用脚本

- `scripts/translate_helper.py` — 翻译辅助工具，提供术语表和格式化
  - 执行方式: `python C:/tmp/skills/translator/scripts/translate_helper.py "待翻译文本"`
  - 功能: 识别专业术语、统计字数、检测语言

## 注意事项

- 使用 shell 工具执行脚本，使用绝对路径
- 翻译时保持原文的语气和风格
- 对专业术语提供多种翻译选项
- 长文本分段翻译，保持上下文连贯
