#!/usr/bin/env python3
"""根据描述生成建表 SQL"""
import sys

def parse_fields(description):
    """解析字段描述"""
    parts = description.split(',')
    if len(parts) < 2:
        return None, parts[0].strip()

    table_desc = parts[0].strip()
    fields = [f.strip() for f in parts[1:]]
    return table_desc, fields

def to_table_name(desc):
    """中文描述转表名"""
    mapping = {
        '用户': 'users', '订单': 'orders', '商品': 'products',
        '文章': 'articles', '评论': 'comments', '分类': 'categories',
        '标签': 'tags', '日志': 'logs', '配置': 'configs',
        '消息': 'messages', '会话': 'sessions', '支付': 'payments',
    }
    for cn, en in mapping.items():
        if cn in desc:
            return en
    return desc.lower().replace(' ', '_')

def guess_type(field):
    """猜测字段类型"""
    field_lower = field.lower()
    if field_lower == 'id':
        return 'BIGINT', 'AUTO_INCREMENT', 'PRIMARY KEY'
    if 'created' in field_lower or 'updated' in field_lower or 'time' in field_lower or 'date' in field_lower:
        return 'DATETIME', 'DEFAULT CURRENT_TIMESTAMP', ''
    if 'email' in field_lower:
        return 'VARCHAR(255)', 'NOT NULL', 'UNIQUE'
    if 'phone' in field_lower or 'mobile' in field_lower:
        return 'VARCHAR(20)', 'NOT NULL', ''
    if 'price' in field_lower or 'amount' in field_lower or 'money' in field_lower:
        return 'DECIMAL(10,2)', 'NOT NULL DEFAULT 0', ''
    if 'status' in field_lower or 'type' in field_lower:
        return 'TINYINT', 'NOT NULL DEFAULT 0', ''
    if 'count' in field_lower or 'num' in field_lower or 'total' in field_lower:
        return 'INT', 'NOT NULL DEFAULT 0', ''
    if 'content' in field_lower or 'text' in field_lower or 'desc' in field_lower:
        return 'TEXT', '', ''
    if 'is_' in field_lower or field_lower.startswith('has_'):
        return 'TINYINT(1)', 'NOT NULL DEFAULT 0', ''
    return 'VARCHAR(255)', 'NOT NULL', ''

def generate_mysql(table_name, fields):
    lines = [f"CREATE TABLE {table_name} ("]
    field_defs = []
    constraints = []
    for f in fields:
        dtype, default, extra = guess_type(f)
        parts = [f"    {f} {dtype}"]
        if default:
            parts.append(default)
        if extra == 'PRIMARY KEY':
            constraints.append(f"    PRIMARY KEY ({f})")
        elif extra:
            parts.append(extra)
        field_defs.append(' '.join(parts))

    field_defs.append("    created_at DATETIME DEFAULT CURRENT_TIMESTAMP")
    field_defs.append("    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")

    all_defs = field_defs + constraints
    lines.append(',\n'.join(all_defs))
    lines.append(f") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='';")
    return '\n'.join(lines)

def generate_postgresql(table_name, fields):
    lines = [f"CREATE TABLE {table_name} ("]
    field_defs = []
    constraints = []
    for f in fields:
        dtype, default, extra = guess_type(f)
        if dtype == 'BIGINT' and 'AUTO_INCREMENT' in default:
            dtype = 'BIGSERIAL'
            default = ''
        if dtype == 'TINYINT':
            dtype = 'SMALLINT'
        if dtype == 'TINYINT(1)':
            dtype = 'BOOLEAN'
            default = 'DEFAULT FALSE' if 'DEFAULT 0' in default else ''
        parts = [f"    {f} {dtype}"]
        if default:
            parts.append(default.replace('DEFAULT CURRENT_TIMESTAMP', "DEFAULT NOW()"))
        if extra == 'PRIMARY KEY':
            constraints.append(f"    PRIMARY KEY ({f})")
        elif extra and extra != 'AUTO_INCREMENT':
            parts.append(extra)
        field_defs.append(' '.join(parts))

    field_defs.append("    created_at TIMESTAMP DEFAULT NOW()")
    field_defs.append("    updated_at TIMESTAMP DEFAULT NOW()")

    all_defs = field_defs + constraints
    lines.append(',\n'.join(all_defs))
    lines.append(");")
    return '\n'.join(lines)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python sql_generator.py \"用户表,包含id,name,email\"")
        sys.exit(1)

    desc = sys.argv[1]
    table_desc, fields = parse_fields(desc)

    if not fields:
        print("请提供字段列表，例如: 用户表,id,name,email,created_at")
        sys.exit(1)

    table_name = to_table_name(table_desc)
    print(f"表名: {table_name}")
    print(f"字段: {fields}")

    print(f"\n=== MySQL ===")
    print(generate_mysql(table_name, fields))

    print(f"\n=== PostgreSQL ===")
    print(generate_postgresql(table_name, fields))
