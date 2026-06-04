import { get, post } from './request'
import type { Skill, SkillDetail, ApiResponse } from '@/types'

// 获取技能列表
export function getSkills(): Promise<ApiResponse<Skill[]>> {
  return get<Skill[]>('/skills')
}

// 获取技能详情
export function getSkillDetail(name: string): Promise<ApiResponse<SkillDetail>> {
  return get<SkillDetail>(`/skills/${name}`)
}

// 获取技能内容
export function getSkillContent(name: string): Promise<ApiResponse<string>> {
  return get<string>(`/skills/${name}/content`)
}

// 热重载技能
export function reloadSkills(): Promise<ApiResponse<unknown>> {
  return post<unknown>('/skills/reload')
}
