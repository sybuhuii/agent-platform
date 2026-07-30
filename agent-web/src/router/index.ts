import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getSessionId } from '@/api/client'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    name: 'Chat',
    component: () => import('@/views/chat/ChatView.vue')
  },
  {
    path: '/approvals',
    name: 'Approvals',
    component: () => import('@/views/approvals/ApprovalsView.vue')
  },
  {
    path: '/admin/users',
    name: 'UserManagement',
    component: () => import('@/views/admin/UserManagementView.vue'),
    meta: { permission: 'security:user:read' }
  },
  {
    path: '/admin/roles',
    name: 'RoleManagement',
    component: () => import('@/views/admin/RoleManagementView.vue'),
    meta: { permission: 'security:role:read' }
  },
  {
    path: '/permission-denied',
    name: 'PermissionDenied',
    component: () => import('@/views/auth/PermissionDeniedView.vue')
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

let authRestored = false

router.beforeEach(async (to) => {
  if (!authRestored) {
    authRestored = true
    const sid = getSessionId()
    if (sid) {
      const authStore = useAuthStore()
      await authStore.restoreAuth()
    }
  }

  const authStore = useAuthStore()

  // 已登录访问 /login 跳转首页
  if (to.path === '/login' && authStore.authenticated) {
    return { path: '/' }
  }

  // 公开页面直接放行
  if (to.meta.public) return true

  // 未登录访问受保护页面跳转 /login
  if (!authStore.authenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 管理页面需要对应权限
  if (to.meta.permission && !authStore.hasPermission(to.meta.permission as string)) {
    return { path: '/permission-denied' }
  }

  return true
})

export default router
