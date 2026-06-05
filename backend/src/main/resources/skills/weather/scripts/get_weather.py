#!/usr/bin/env python3
"""天气查询脚本 - 接入和风天气 API（免费版）"""
import sys
import os
import json
import requests

API_KEY = os.environ.get("QWEATHER_API_KEY", "")
API_HOST = os.environ.get("QWEATHER_API_HOST", "nc5hv77ymj.re.qweatherapi.com")
GEO_URL = f"https://{API_HOST}/geo/v2/city/lookup"
WEATHER_URL = f"https://{API_HOST}/v7/weather/now"
FORECAST_URL = f"https://{API_HOST}/v7/weather/3d"


def check_api_error(resp, action):
    """检查 API 响应，返回错误信息或 None"""
    try:
        data = resp.json()
    except Exception:
        return f"API 返回非 JSON 内容 (HTTP {resp.status_code})"

    code = data.get("code")
    if code == "200":
        return None

    # 和风天气错误码
    error_map = {
        "200": "请求成功",
        "400": "请求错误，可能参数有误",
        "401": "API Key 无效或未激活",
        "402": "API 请求超过每日配额",
        "403": "无访问权限（检查控制台是否设置了 Host 白名单）",
        "404": "查询的地区不存在",
        "429": "超过限定的 QPM（每分钟请求数）",
        "500": "服务器内部错误",
    }

    status = data.get("error", {}).get("status", code)
    detail = data.get("error", {}).get("detail", "")

    if detail:
        return f"{action}失败: {detail}"
    desc = error_map.get(str(status), f"未知错误 (code={code})")
    return f"{action}失败: {desc}"


def lookup_city(city_name):
    """通过 GeoAPI 查询城市 ID"""
    resp = requests.get(GEO_URL, params={
        "location": city_name,
        "key": API_KEY,
        "number": 1
    }, timeout=10)

    err = check_api_error(resp, "城市查询")
    if err:
        print(f"错误: {err}", file=sys.stderr)
        return None

    data = resp.json()
    if not data.get("location"):
        return None
    loc = data["location"][0]
    return {
        "id": loc["id"],
        "name": loc["name"],
        "adm1": loc.get("adm1", ""),
        "adm2": loc.get("adm2", ""),
        "country": loc.get("country", "")
    }


def get_current_weather(city_id):
    """获取实时天气"""
    resp = requests.get(WEATHER_URL, params={
        "location": city_id,
        "key": API_KEY
    }, timeout=10)

    err = check_api_error(resp, "天气查询")
    if err:
        print(f"错误: {err}", file=sys.stderr)
        return None
    return resp.json().get("now", {})


def get_forecast(city_id):
    """获取未来 3 天天气预报"""
    resp = requests.get(FORECAST_URL, params={
        "location": city_id,
        "key": API_KEY
    }, timeout=10)

    err = check_api_error(resp, "天气预报")
    if err:
        return []
    return resp.json().get("daily", [])


def format_location(city_info):
    """格式化城市全称"""
    parts = []
    if city_info["adm1"]:
        parts.append(city_info["adm1"])
    if city_info["name"] != city_info["adm1"]:
        parts.append(city_info["name"])
    return " ".join(parts) if parts else city_info["name"]


def main():
    if not API_KEY:
        print("错误: 未配置 QWEATHER_API_KEY 环境变量")
        print("请在 .env 文件中设置: QWEATHER_API_KEY=你的key")
        sys.exit(1)

    city_name = sys.argv[1] if len(sys.argv) > 1 else "北京"

    # 查询城市
    city_info = lookup_city(city_name)
    if not city_info:
        print(f"错误: 未找到城市 \"{city_name}\"")
        sys.exit(1)

    location_name = format_location(city_info)

    # 获取实时天气
    now = get_current_weather(city_info["id"])
    if not now:
        sys.exit(1)

    # 输出实时天气
    print(f"【{location_name} 实时天气】")
    print(f"  天气: {now.get('text', '未知')}")
    print(f"  温度: {now.get('temp', '未知')}°C（体感 {now.get('feelsLike', '未知')}°C）")
    print(f"  湿度: {now.get('humidity', '未知')}%")
    print(f"  风向: {now.get('windDir', '未知')} {now.get('windScale', '未知')}级")
    print(f"  风速: {now.get('windSpeed', '未知')} km/h")
    print(f"  气压: {now.get('pressure', '未知')} hPa")
    print(f"  能见度: {now.get('vis', '未知')} km")
    print(f"  更新时间: {now.get('obsTime', '未知')}")

    # 获取 3 天预报
    forecast = get_forecast(city_info["id"])
    if forecast:
        print(f"\n【{location_name} 未来 3 天预报】")
        for day in forecast:
            print(f"  {day.get('fxDate', '')}: "
                  f"{day.get('textDay', '未知')}转{day.get('textNight', '未知')}, "
                  f"气温 {day.get('tempMin', '?')}~{day.get('tempMax', '?')}°C, "
                  f"{day.get('windDirDay', '')} {day.get('windScaleDay', '?')}级")


if __name__ == "__main__":
    main()
