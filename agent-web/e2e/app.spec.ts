/**
 * E2E 测试 — 登录、布局、主题、交互模型、会话切换、样式验证。
 *
 * 业务模型：用户 → 系统 → 唯一 Supervisor → 内部 Agent → Tool
 * - 登录后不得出现 Supervisor 选择器或 Agent 选择器
 * - 登录后直接进入聊天
 * - 不展示 Supervisor/Agent 技术名称
 * - 会话切换不丢失消息
 * - Tailwind 样式真实生效
 */
import { test, expect } from '@playwright/test'

test.describe('登录页', () => {
  test('登录页渲染', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByText('Agent Platform')).toBeVisible()
    await expect(page.getByLabel('用户名')).toBeVisible()
    await expect(page.getByLabel('密码')).toBeVisible()
  })

  test('未登录访问正式页面跳转登录', async ({ page }) => {
    await page.goto('/')
    await page.waitForURL(/\/login/, { timeout: 5000 })
    expect(page.url()).toContain('/login')
  })
})

test.describe('交互模型', () => {
  test.skip(() => !process.env.E2E_USERNAME, '需要 E2E_USERNAME 环境变量')

  test('登录后不得出现 Supervisor 选择器', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    // 不得出现 Supervisor 或 Agent 选择器
    await expect(page.getByText(/选择.*Supervisor/i)).not.toBeVisible()
    await expect(page.getByText(/选择 Agent/i)).not.toBeVisible()
  })

  test('登录后不展示技术名称', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    // 不展示 Supervisor 技术名称
    await expect(page.getByText('general_supervisor')).not.toBeVisible()
  })

  test('登录后直接进入聊天', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    // 应该看到输入框
    await expect(page.getByPlaceholder('输入消息...')).toBeVisible()
  })
})

test.describe('桌面布局', () => {
  test.skip(() => !process.env.E2E_USERNAME, '需要 E2E_USERNAME 环境变量')

  test('桌面布局基础检查', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    await expect(page.getByText('新建对话')).toBeVisible()
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth)
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1)
  })

  test('Sidebar 宽度约 300px', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    // 检查侧边栏容器宽度
    const sidebarWidth = await page.evaluate(() => {
      const aside = document.querySelector('aside')
      return aside ? aside.getBoundingClientRect().width : 0
    })
    expect(sidebarWidth).toBeGreaterThanOrEqual(280)
    expect(sidebarWidth).toBeLessThanOrEqual(320)
  })

  test('Sidebar 使用 flex 布局', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    const sidebarDisplay = await page.evaluate(() => {
      const aside = document.querySelector('aside')
      return aside ? getComputedStyle(aside).display : ''
    })
    expect(sidebarDisplay).toBe('flex')
  })

  test('根布局横向排列', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    const rootDisplay = await page.evaluate(() => {
      const root = document.querySelector('.flex.h-dvh')
      return root ? getComputedStyle(root).display : ''
    })
    expect(rootDisplay).toBe('flex')
  })

  test('产品标识"智能协作"可见', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    await expect(page.getByText('智能协作')).toBeVisible()
  })

  test('空状态显示"有什么可以帮你？"', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    // 等待系统初始化完成
    await page.waitForTimeout(2000)
    await expect(page.getByText('有什么可以帮你？')).toBeVisible()
  })

  test('页面无默认蓝色链接样式', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    // 检查没有默认蓝色链接
    const hasDefaultLink = await page.evaluate(() => {
      const links = document.querySelectorAll('a')
      for (const link of links) {
        const style = getComputedStyle(link)
        if (style.color === 'rgb(0, 0, 238)' || style.color === 'rgb(0, 0, 255)') {
          return true
        }
        if (style.textDecoration === 'underline' && style.color !== getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()) {
          return true
        }
      }
      return false
    })
    expect(hasDefaultLink).toBe(false)
  })

  test('按钮具有圆角', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    const newChatBtnRadius = await page.evaluate(() => {
      const btn = document.querySelector('button')
      return btn ? getComputedStyle(btn).borderRadius : ''
    })
    // 按钮应该有圆角（不是 0px）
    expect(newChatBtnRadius).not.toBe('0px')
  })

  test('输入框位于底部', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    const inputPosition = await page.evaluate(() => {
      const textarea = document.querySelector('textarea')
      const appRoot = document.querySelector('#app')
      if (!textarea || !appRoot) return { bottom: 0, appHeight: 0 }
      return {
        bottom: textarea.getBoundingClientRect().bottom,
        appHeight: appRoot.getBoundingClientRect().height
      }
    })
    // 输入框应该在页面底部 100px 以内
    expect(inputPosition.appHeight - inputPosition.bottom).toBeLessThan(100)
  })
})

test.describe('移动端', () => {
  test.skip(() => !process.env.E2E_USERNAME, '需要 E2E_USERNAME 环境变量')

  test('移动端菜单打开与 Esc 关闭', async ({ page }) => {
    const username = process.env.E2E_USERNAME!
    const password = process.env.E2E_PASSWORD ?? ''
    await page.goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL(/\//, { timeout: 10000 })

    const sidebar = page.getByText('新建对话')
    await page.getByLabel('打开菜单').click()
    await expect(sidebar).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(sidebar).not.toBeVisible()
  })
})
