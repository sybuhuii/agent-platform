// 权限编码集中配置，不得在多个组件重复散落
export const TOOL_PERMISSIONS = [
  { code: 'tool:*:invoke', label: '全部工具调用权限', isWildcard: true },
  { code: 'tool:calculator:invoke', label: 'calculator 工具调用' },
  { code: 'tool:current_time:invoke', label: 'current_time 工具调用' },
  { code: 'tool:echo:invoke', label: 'echo 工具调用' },
  { code: 'tool:text_search:invoke', label: 'text_search 工具调用' }
] as const

export const MANAGEMENT_PERMISSIONS = [
  { code: 'security:user:read', label: '用户查看' },
  { code: 'security:user:write', label: '用户管理' },
  { code: 'security:role:read', label: '角色查看' },
  { code: 'security:role:write', label: '角色管理' },
  { code: 'security:session:revoke', label: 'Session撤销' }
] as const

export const ALL_PERMISSIONS = [...TOOL_PERMISSIONS, ...MANAGEMENT_PERMISSIONS] as const

export function getPermissionLabel(code: string): string {
  const found = ALL_PERMISSIONS.find(p => p.code === code)
  return found ? found.label : code
}
