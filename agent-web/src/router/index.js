import { createRouter, createWebHistory } from 'vue-router'
import { useAuth, restoreAuth, hasPermission } from '../stores/authStore.js'
import { getSessionId } from '../stores/authSessionStore.js'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('../views/DashboardView.vue')
  },
  {
    path: '/agents',
    name: 'AgentInvoke',
    component: () => import('../views/AgentInvokeView.vue')
  },
  {
    path: '/supervisors',
    name: 'SupervisorInvoke',
    component: () => import('../views/SupervisorInvokeView.vue')
  },
  {
    path: '/permission-demo',
    name: 'PermissionDemo',
    component: () => import('../views/PermissionDemoView.vue')
  },
  {
    path: '/approval',
    name: 'HitlApproval',
    component: () => import('../views/HitlApprovalView.vue')
  },
  {
    path: '/admin/users',
    name: 'UserManagement',
    component: () => import('../views/UserManagementView.vue'),
    meta: { permission: 'security:user:read' }
  },
  {
    path: '/admin/roles',
    name: 'RoleManagement',
    component: () => import('../views/RoleManagementView.vue'),
    meta: { permission: 'security:role:read' }
  },
  {
    path: '/permission-denied',
    name: 'PermissionDenied',
    component: () => import('../views/PermissionDeniedView.vue')
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('../views/NotFoundView.vue'),
    meta: { public: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

let authRestored = false

router.beforeEach(async (to) => {
  // 刷新页面后先恢复认证状态
  if (!authRestored) {
    authRestored = true
    const sid = getSessionId()
    if (sid) {
      await restoreAuth()
    }
  }

  const auth = useAuth()

  // 已登录访问 /login 跳转首页
  if (to.path === '/login' && auth.authenticated) {
    return { path: '/' }
  }

  // 公开页面直接放行
  if (to.meta.public) return true

  // 未登录访问受保护页面跳转 /login
  if (!auth.authenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 管理页面需要对应权限
  if (to.meta.permission && !hasPermission(to.meta.permission)) {
    return { path: '/permission-denied' }
  }

  return true
})

export default router
