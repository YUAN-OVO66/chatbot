#!/usr/bin/env python3
"""翻译辅助工具：语言检测、术语识别、字数统计"""
import sys
import re

def detect_language(text):
    """简单语言检测"""
    chinese_chars = len(re.findall(r'[一-鿿]', text))
    total_chars = len(text.strip())
    if total_chars == 0:
        return "空文本"
    ratio = chinese_chars / total_chars
    if ratio > 0.3:
        return "中文"
    elif ratio > 0.05:
        return "中英混合"
    else:
        return "英文"

def count_stats(text):
    """字数统计"""
    chinese_chars = len(re.findall(r'[一-鿿]', text))
    english_words = len(re.findall(r'[a-zA-Z]+', text))
    total_chars = len(text.strip())
    return {
        'total_chars': total_chars,
        'chinese_chars': chinese_chars,
        'english_words': english_words,
    }

# 常见专业术语表
GLOSSARY = {
    '机器学习': 'Machine Learning',
    '深度学习': 'Deep Learning',
    '神经网络': 'Neural Network',
    '自然语言处理': 'Natural Language Processing (NLP)',
    '计算机视觉': 'Computer Vision',
    '微服务': 'Microservices',
    '容器化': 'Containerization',
    '持续集成': 'Continuous Integration (CI)',
    '持续部署': 'Continuous Deployment (CD)',
    '负载均衡': 'Load Balancing',
    '数据库': 'Database',
    '缓存': 'Cache',
    '消息队列': 'Message Queue',
    '分布式': 'Distributed',
    '高可用': 'High Availability (HA)',
    '并发': 'Concurrency',
    '线程安全': 'Thread Safety',
    '设计模式': 'Design Pattern',
    '依赖注入': 'Dependency Injection (DI)',
    '面向对象': 'Object-Oriented',
    '函数式编程': 'Functional Programming',
    'API网关': 'API Gateway',
    '服务发现': 'Service Discovery',
    '熔断器': 'Circuit Breaker',
    '限流': 'Rate Limiting',
}

def find_terms(text):
    """查找文本中的专业术语"""
    found = []
    for cn, en in GLOSSARY.items():
        if cn in text:
            found.append((cn, en))
    return found

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python translate_helper.py \"待分析文本\"")
        sys.exit(1)

    text = sys.argv[1]

    print("=== 语言检测 ===")
    lang = detect_language(text)
    print(f"  检测结果: {lang}")

    print("\n=== 字数统计 ===")
    stats = count_stats(text)
    print(f"  总字符数: {stats['total_chars']}")
    print(f"  中文字符: {stats['chinese_chars']}")
    print(f"  英文单词: {stats['english_words']}")

    print("\n=== 识别到的术语 ===")
    terms = find_terms(text)
    if terms:
        for cn, en in terms:
            print(f"  {cn} → {en}")
    else:
        print("  未识别到专业术语")
