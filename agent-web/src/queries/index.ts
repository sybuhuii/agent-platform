/**
 * TanStack Vue Query hooks — 管理服务端数据查询。
 *
 * 业务模型：用户只与 Supervisor 交互。
 * - useSupervisors 是用户选择 Supervisor 的数据来源
 * - useAgents 仅供管理页面参考，不用于用户选择
 * - AbortSignal 从 queryFn 传入 API
 */
import { useQuery } from '@tanstack/vue-query'
import * as frameworkApi from '@/api/framework'
import * as approvalApi from '@/api/approvals'
import * as usersApi from '@/api/users'
import * as rolesApi from '@/api/roles'

// ─── Supervisor 查询（用户可见） ───

export function useSupervisors() {
  return useQuery({
    queryKey: ['framework', 'supervisors'],
    queryFn: ({ signal }) => frameworkApi.listSupervisors(signal),
    staleTime: 5 * 60 * 1000
  })
}

// ─── 工具查询 ───

export function useTools() {
  return useQuery({
    queryKey: ['framework', 'tools'],
    queryFn: ({ signal }) => frameworkApi.listTools(signal),
    staleTime: 5 * 60 * 1000
  })
}

// ─── 审批查询 ───

export function usePendingApprovals() {
  return useQuery({
    queryKey: ['hitl', 'approvals'],
    queryFn: ({ signal }) => approvalApi.listPendingApprovals(signal),
    staleTime: 30 * 1000,
    refetchInterval: 60 * 1000
  })
}

// ─── 用户管理查询 ───

export function useUsers() {
  return useQuery({
    queryKey: ['admin', 'users'],
    queryFn: ({ signal }) => usersApi.listUsers(signal),
    staleTime: 2 * 60 * 1000
  })
}

// ─── 角色管理查询 ───

export function useRoles() {
  return useQuery({
    queryKey: ['admin', 'roles'],
    queryFn: ({ signal }) => rolesApi.listRoles(signal),
    staleTime: 2 * 60 * 1000
  })
}
