#!/usr/bin/env python3
"""SQL 语法验证与格式化"""
import sys
import re

def format_sql(sql):
    """简单格式化 SQL"""
    keywords = [
        'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'ORDER BY', 'GROUP BY',
        'HAVING', 'LIMIT', 'OFFSET', 'INSERT INTO', 'VALUES', 'UPDATE',
        'SET', 'DELETE FROM', 'CREATE TABLE', 'ALTER TABLE', 'DROP TABLE',
        'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'ON', 'AS',
        'UNION', 'UNION ALL', 'DISTINCT', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END'
    ]
    result = sql.strip()
    for kw in keywords:
        pattern = re.compile(r'\b(' + re.escape(kw) + r')\b', re.IGNORECASE)
        result = pattern.sub(r'\n\1', result)
    return result.strip()

def validate_sql(sql):
    """基础语法检查"""
    issues = []
    sql_upper = sql.upper().strip()

    if not sql_upper:
        issues.append("SQL 语句为空")
        return issues

    valid_starts = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'ALTER', 'DROP', 'WITH', 'EXPLAIN']
    if not any(sql_upper.startswith(s) for s in valid_starts):
        issues.append(f"语句应以有效关键字开头: {', '.join(valid_starts)}")

    if sql_upper.startswith('SELECT'):
        if ' FROM ' not in sql_upper and 'FROM' not in sql_upper:
            issues.append("SELECT 语句缺少 FROM 子句")

    open_parens = sql.count('(')
    close_parens = sql.count(')')
    if open_parens != close_parens:
        issues.append(f"括号不匹配: ( x{open_parens}, ) x{close_parens}")

    if sql.strip().endswith(','):
        issues.append("语句末尾有多余的逗号")

    single = sql.count("'")
    if single % 2 != 0:
        issues.append("单引号不匹配")

    return issues

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python sql_validator.py \"SQL语句\"")
        sys.exit(1)

    sql = sys.argv[1]
    print(f"=== 原始 SQL ===")
    print(sql)

    print(f"\n=== 格式化 ===")
    print(format_sql(sql))

    print(f"\n=== 语法检查 ===")
    issues = validate_sql(sql)
    if issues:
        for i, issue in enumerate(issues, 1):
            print(f"  {i}. {issue}")
    else:
        print("  未发现明显问题")
