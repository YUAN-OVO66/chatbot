---
name: devops
description: 运维助手。当用户询问服务器状态、系统监控、磁盘内存CPU使用情况、进程管理时使用此技能。
---

# 运维助手技能

## 流程

1. 确认用户需要查询的运维信息类型
2. 调用对应脚本获取系统信息
3. 解读结果并给出建议

## 可用脚本

- `scripts/system_info.py` — 获取系统概况（CPU、内存、磁盘）
  - 执行方式: `python C:/tmp/skills/devops/scripts/system_info.py`
  - 返回: 系统运行时间、CPU使用率、内存使用率、磁盘使用率

- `scripts/top_processes.py` — 获取占用资源最多的进程
  - 执行方式: `python C:/tmp/skills/devops/scripts/top_processes.py [数量]`
  - 参数: 数量（可选，默认5）
  - 返回: CPU/内存占用最高的进程列表

- `scripts/check_port.py` — 检查端口是否开放
  - 执行方式: `python C:/tmp/skills/devops/scripts/check_port.py --host <主机> --port <端口>`
  - 返回: 端口状态（开放/关闭）

## 注意事项

- 使用 shell 工具执行脚本，使用绝对路径
- 对系统信息进行分析并给出建议，不要只罗列数据
