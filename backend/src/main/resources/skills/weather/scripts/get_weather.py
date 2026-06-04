#!/usr/bin/env python3
"""天气查询脚本"""
import sys
import json

city = sys.argv[1] if len(sys.argv) > 1 else "北京"

# 模拟天气数据（实际使用时替换为真实 API 调用）
weather_data = {
    "北京": {"condition": "晴", "temp": 28, "humidity": 45},
    "上海": {"condition": "多云", "temp": 26, "humidity": 65},
    "广州": {"condition": "阵雨", "temp": 32, "humidity": 80},
    "深圳": {"condition": "晴转多云", "temp": 31, "humidity": 75},
}

data = weather_data.get(city, {"condition": "晴", "temp": 25, "humidity": 50})
print(f"{city}今天{data['condition']}，气温{data['temp']}°C，湿度{data['humidity']}%")
