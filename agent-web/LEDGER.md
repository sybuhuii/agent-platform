# Agent Web 前端修复 Ledger

## 修复日期：2026-07-30

---

## FIX-001: ChatTransport AbortSignal 未传入 API
**状态：已修复**

- ChatTransport 接口新增 `signal: AbortSignal` 参数
- jsonTransport 将 signal 传入 invokeAgent/invokeSupervisor
- API 函数签名新增 `signal?: AbortSignal`，传入 `post`/`get`
- Chat Store 的 `abortController.signal` 通过 Transport → API → fetch 全链路传递
- 删除了 jsonTransport 中独立的 `currentAbortController`（与 Store 的 AbortController 重复）

## FIX-002: 重复 AbortController
**状态：已修复**

- Chat Store 创建唯一的 `AbortController`，signal 传入 Transport
- jsonTransport 不再创建自己的 AbortController
- `cancelRequest()` 调用 `abortController.abort()`，信号通过 fetch AbortError 传播
- AbortRequestError 明确区分用户主动取消 vs 网络断开
- `newConversation()` 和 `switchThread()` 也调用 `abort()` 清理旧请求

## FIX-003: Client `data as T` 不安全
**状态：已修复**

- `request<T>` 改为 `requestWithSchema<T>(url, schema, options)`
- `get`/`post`/`put` 接收 Zod Schema 参数，自动校验响应
- 所有 API 模块（auth, agents, supervisors, approvals, framework, users, roles）传入对应 Schema
- 新增 `SchemaValidationError` 错误类型，校验失败时抛出
- 不再泄漏原始响应数据到错误消息

## FIX-004: MarkdownContent 未接入 CodeBlock
**状态：已修复**

- 使用自定义 fence renderer 输出 `data-code-block` 安全占位符
- DOMPurify 保留 `data-code-block`、`data-lang`、`data-code` 属性
- 从 sanitized HTML 中解析代码块信息，用 Vue 组件渲染
- 非代码 HTML 由 DOMPurify 清理后通过 v-html 渲染
- 代码块由 CodeBlock 组件渲染，不通过 v-html

## FIX-005: CodeBlock `as any` 和 Timer 清理
**状态：已修复**

- 删除 `lang.value as any`，使用安全语言映射白名单
- `copyTimer` 在 `onUnmounted` 中清理
- 新增 `copyFailed` 状态，复制失败提供克制的反馈
- 使用 `shiki/bundle/web` 的 `createHighlighter` 按需加载语言
- 不导入完整语言集合到首屏

## FIX-006: 审批 QueryClient 生命周期
**状态：已修复**

- 删除 `invalidateApprovals()` 全局函数
- ApprovalsView 在组件 setup 中通过 `useQueryClient()` 获取 client
- 审批操作后直接调用 `queryClient.invalidateQueries()`
- 每个审批项有独立的拒绝原因输入（通过 Dialog）
- 不因一个审批项操作影响其他项的输入状态

## FIX-007: 401 集中处理
**状态：已修复**

- 新增 `registerUnauthorizedHandler()` 注册回调
- main.ts 中注册 401 处理器：清空认证状态 + 跳转登录页
- Client 不直接 import router 或 authStore，避免循环依赖
- 登录接口自身的 401 不触发过期跳转
- 403 不清除 Session

## FIX-008: 枚举不一致
**状态：已修复**

- `ApprovalStatus`: 删除 `EXPIRED`，保留 `PENDING | APPROVED | REJECTED`
- `ToolRiskLevel`: 删除 `CRITICAL`，新增 `SAFE`，现在为 `SAFE | LOW | MEDIUM | HIGH`
- Zod schemas 新增 `approvalStatusSchema`、`toolRiskLevelSchema`、`runStatusSchema` 共享枚举
- 所有 Schema 和组件引用已更新

## FIX-009: `any` 和不安全断言
**状态：已修复**

- ChatMessage.vue 中 `(message as any).toolName` → 使用 `toolResultMsg` computed + 类型缩窄
- Chat Store 中 `(patch as Record<string, unknown>).status` → 直接在 patch 对象中设置 `status`
- CodeBlock.vue 中 `lang.value as any` → 安全映射白名单
- 新增 `AbortRequestError`、`SchemaValidationError` 错误类型

## FIX-010: Reka UI/shadcn-vue 实际使用
**状态：已修复**

- 创建 `Dialog.vue` 组件（基于 Reka UI DialogRoot）
- 创建 `Sheet.vue` 组件（基于 Reka UI Dialog，侧边抽屉）
- AppLayout 移动端侧边栏使用 Sheet 替代手写 Teleport
- ApprovalsView 拒绝原因弹窗使用 Dialog
- 所有组件支持 Esc 关闭、焦点锁定、无障碍标题

## FIX-011: Lucide 图标实际使用
**状态：已修复**

- 安装 `@lucide/vue`，卸载弃用的 `lucide-vue-next`
- Sidebar 使用：Plus, Bell, Users, Shield, Sun, Moon, Monitor, LogOut
- ChatComposer 使用：Send, Square
- AppLayout 使用：Menu, PanelLeftClose
- ChatView 使用：ArrowDown（回到底部按钮）

## FIX-012: 不可达组件处理
**状态：已保留**

- ToolCallCard 和 SupervisorDispatchCard 保留但当前无真实数据来源
- 后端不返回实时工具调用和 Supervisor 分派事件
- 组件将在后端支持时接入，不删除

## FIX-013: Playwright 配置
**状态：已创建**

- 安装 `@playwright/test`
- 创建 `playwright.config.ts`（chromium + mobile）
- 创建 `e2e/app.spec.ts`（登录页、路由保护、布局、主题、移动端）
- 真实 Agent/Supervisor 流程需要运行中的后端，用环境变量跳过
- 不得用 Mock Agent 冒充 E2E
- 未安装浏览器（需要运行 `npx playwright install`）

## FIX-014: Chat Store SUSPENDED 状态优先级
**状态：已修复**

- `handleResponse` 中 `SUSPENDED` 判断优先于 `success`
- 后端 SUSPENDED 返回 `success=false`，但不映射为 `failed` 状态
- 审批卡片从 `response.metadata` 提取 `approvalId`、`operationName`、`riskLevel`

## FIX-015: 补充测试
**状态：已修复**

- 新增 `api-client.test.ts`：Zod 校验、AbortError 区分、401/403 行为
- 新增 `chat-store.test.ts`：状态转换、mode 设置、newConversation、cancelRequest
- 测试总数从 23 增加到 36

---

## 验证结果

| 检查项 | 结果 |
|--------|------|
| TypeScript 类型检查 | ✅ 通过（0 错误） |
| 单元测试 | ✅ 36/36 通过 |
| 生产构建 | ✅ 成功（5.7MB，含 Shiki 语言包） |
| Playwright E2E | ⚠️ 配置已创建，浏览器未安装，需 `npx playwright install` |
| 枚举一致性 | ✅ 与后端 Java 枚举完全一致 |
| `any` 搜索 | ✅ 无 `as any` 残留 |
| Reka UI 使用 | ✅ Dialog, Sheet, DialogRoot, DialogContent 等 |
| Lucide 使用 | ✅ @lucide/vue，10+ 图标 |
| 不可达组件 | ✅ 保留，标注数据来源缺失 |
| 深色主题 | ✅ CSS 变量 + .dark 类切换 |
| 移动端 | ✅ Sheet 抽屉 + Esc 关闭 + 焦点锁定 |

## 未完成验证及准确原因

1. **Playwright 浏览器未安装** — `npx playwright install` 需要下载 Chromium，当前环境未执行
2. **真实后端 E2E 测试** — 需要运行中的后端和 E2E 凭据环境变量
3. **Shiki 包体积** — `shiki/bundle/web` 仍包含所有 web 语言，5.7MB。等待 Shiki fine-grained imports

## 发现但未处理的非本次问题

1. 后端无 SSE/流式响应 — 前端已实现 ChatTransport 抽象，后续可扩展
2. 后端无线程历史查询接口 — 切换线程时无法恢复消息
3. 后端无实时工具调用事件 — ToolCallCard 无法从后端获取真实数据
4. Shiki 包体积较大 — 需要等待 Shiki fine-grained imports 或考虑其他方案
