---
name: weather
description: 查询指定城市的天气信息。当用户问天气、气温、温度时使用此技能。
---

# 天气查询技能

## 流程

1. 确认用户要查询的城市
2. 调用脚本查询天气并返回结果

## 可用脚本

- `scripts/get_weather.py` — 查询城市天气
  - 执行方式: `python C:/tmp/skills/weather/scripts/get_weather.py <城市名>`
  - 返回: 天气状况、温度、湿度信息

## 注意事项

- 如果用户未指定城市，默认查询北京
- 使用 shell 工具执行脚本，使用绝对路径
