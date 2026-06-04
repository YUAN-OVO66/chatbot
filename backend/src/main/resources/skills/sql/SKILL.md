---
name: sql
description: SQL 助手。当用户需要编写SQL查询、优化SQL语句、解释SQL逻辑、生成建表语句时使用此技能。
---

# SQL 助手技能

## 流程

1. 确认用户的数据库类型（MySQL/PostgreSQL/SQLite等）和需求
2. 生成或优化 SQL 语句
3. 使用 sqlite3 验证 SQL 语法正确性（如适用）

## 可用脚本

- `scripts/sql_validator.py` — 验证 SQL 语法并格式化
  - 执行方式: `python C:/tmp/skills/sql/scripts/sql_validator.py "SELECT * FROM users"`
  - 参数: SQL 语句（引号包裹）
  - 返回: 格式化后的 SQL 和语法检查结果

- `scripts/sql_generator.py` — 根据描述生成建表语句
  - 执行方式: `python C:/tmp/skills/sql/scripts/sql_generator.py "用户表,包含id,name,email,created_at"`
  - 参数: 表描述
  - 返回: 建表 SQL（MySQL 和 PostgreSQL 两套）

## 注意事项

- 使用 shell 工具执行脚本，使用绝对路径
- 优先给出标准 SQL，再标注数据库特定语法差异
- 对复杂查询建议添加索引或优化方案
