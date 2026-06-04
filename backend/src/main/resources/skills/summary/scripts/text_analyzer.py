#!/usr/bin/env python3
"""文本分析工具：字数统计、段落分析、关键词提取"""
import sys
import re
from collections import Counter

def analyze_text(text):
    """基础文本分析"""
    if not text.strip():
        return {"error": "文本为空"}

    lines = [l for l in text.split('\n') if l.strip()]
    paragraphs = [p.strip() for p in re.split(r'\n\s*\n', text) if p.strip()]
    chinese_chars = len(re.findall(r'[一-鿿]', text))
    english_words = re.findall(r'[a-zA-Z]+', text)
    sentences = re.split(r'[。！？.!?]+', text)
    sentences = [s.strip() for s in sentences if s.strip()]

    return {
        'total_chars': len(text),
        'chinese_chars': chinese_chars,
        'english_word_count': len(english_words),
        'line_count': len(lines),
        'paragraph_count': len(paragraphs),
        'sentence_count': len(sentences),
        'avg_sentence_len': round(chinese_chars / max(len(sentences), 1), 1),
    }

def extract_keywords(text, top_n=10):
    """提取关键词（基于词频的简单方法）"""
    # 提取中文词组（2-4字）
    cn_words = re.findall(r'[一-鿿]{2,4}', text)
    # 提取英文单词
    en_words = [w.lower() for w in re.findall(r'[a-zA-Z]{3,}', text)]

    # 停用词
    cn_stopwords = {'的', '了', '在', '是', '我', '有', '和', '就', '不', '人', '都', '一', '一个',
                    '上', '也', '很', '到', '说', '要', '去', '你', '会', '着', '没有', '看', '好',
                    '自己', '这', '他', '她', '它', '们', '那', '些', '什么', '怎么', '如何', '可以',
                    '但是', '因为', '所以', '如果', '虽然', '或者', '以及', '而且', '但', '而', '被',
                    '把', '对', '从', '为', '以', '与', '等', '这个', '那个'}
    en_stopwords = {'the', 'and', 'for', 'are', 'but', 'not', 'you', 'all', 'can', 'had',
                    'her', 'was', 'one', 'our', 'out', 'has', 'have', 'been', 'from', 'this',
                    'that', 'with', 'they', 'will', 'each', 'make', 'like', 'time', 'just',
                    'know', 'take', 'people', 'into', 'year', 'your', 'some', 'them', 'than'}

    cn_filtered = [w for w in cn_words if w not in cn_stopwords]
    en_filtered = [w for w in en_words if w not in en_stopwords]

    all_words = cn_filtered + en_filtered
    counter = Counter(all_words)
    return counter.most_common(top_n)

def readability_score(text):
    """可读性评估"""
    stats = analyze_text(text)
    avg_len = stats['avg_sentence_len']
    if avg_len < 15:
        return "简单 — 短句为主，易于理解"
    elif avg_len < 30:
        return "中等 — 句子长度适中"
    else:
        return "复杂 — 长句较多，可能需要精简"

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python text_analyzer.py \"文本内容\"")
        sys.exit(1)

    text = sys.argv[1]

    print("=== 文本统计 ===")
    stats = analyze_text(text)
    for k, v in stats.items():
        print(f"  {k}: {v}")

    print(f"\n=== 可读性 ===")
    print(f"  {readability_score(text)}")

    print(f"\n=== 关键词 (Top 10) ===")
    keywords = extract_keywords(text)
    for word, count in keywords:
        print(f"  {word} ({count}次)")
