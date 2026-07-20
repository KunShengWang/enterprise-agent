# Unified Agent Workbench V1 — M1-D 缺口矩阵

> 日期：2026-07-20 CST
> 蓝图：V0.2.3 / FINAL
> 前置门禁：M1-C PASSED（`08aacbd`）

| 蓝图要求 | 当前代码事实 | 缺失内容 | M1-D 计划 | 测试/证据 | 设计分支 |
|---|---|---|---|---|---|
| Tenant / Principal 隔离门禁 | M1-D-S0 已修复 Conversation、Focus、Input、WorkItem、Event、Relation、Link、Command、Routing、Preview、DispatchAttempt 查询链 | 无阻塞项 | 后续 Controller 只能调用显式接收 `AuthenticatedPrincipal` 的 Store/Application API | `WorkbenchTenantIsolationPostgresIT`；S0 evidence | 已冻结为 M1-D 前置门禁并通过 |
| 统一自然语言入口 | M1-A～C 只有应用服务，无统一 Controller | 输入 API、稳定 input/work 响应、异步 route/dispatch | 新建 `UnifiedWorkController` 和有界 `UnifiedWorkLauncher` | Web 单元 + PostgreSQL E2E | 无 |
| 身份不可来自请求体 | 现有 Workbench 服务接收 `AuthenticatedPrincipal`，应用尚无认证框架 | 服务端本地身份适配，禁止 userId/tenant/roles/profile metadata | 使用服务端配置的 local principal provider；请求体无身份字段 | metadata 越权拒绝测试 | 无；完整认证平台仍非 V1 范围 |
| WorkItem/Focus/历史查询 | Store 已有单项查询与事件查询 | Conversation 输入、WorkItem 列表和聚合详情 | 增加受所有权约束的只读查询 | PostgreSQL 所有权隔离 | 无 |
| Incident Preview 显式确认 | M1-C 有应用服务，无 API/卡片 | 查询 Preview、绑定版本和双 digest 确认/拒绝 | 确认按钮先持久化 input，再调用确认服务并异步 dispatch | 未确认零执行；篡改拒绝 | 无 |
| 四目标最小结果入口 | WorkLink 已保存 Run/Incident/Plan ID | 统一详情返回 target link 与旧专项页面跳转信息 | 聚合 WorkItem、Decision、Preview、Link、M1 本地 Event | 四目标视图模型测试 | 无 |
| 命令不支持结构化错误 | 分类可识别命令，但 M3-A 前无跨执行器 Handler | 禁止伪造命令成功 | 自然语言命令先落库，再返回 `UNSUPPORTED_FOR_TARGET`/`FOCUS_NOT_FOUND` 等结构化 409 | 不创建新 WorkItem、不改底层状态 | 无；真实命令执行保留 M3-A |
| 最小统一前端 | 现有 Runtime 与 Incident 是分离页面 | UnifiedWorkbench、输入、列表、Focus、路由卡、Preview 卡、结果跳转 | 新 Vue 页面与轻量轮询；复用全局样式和现有专项页面 | typecheck/build + 页面交互测试 | 无 |
| M2 边界 | 现有页面各自展示 Timeline | 不得提前复制跨源时间线/执行树 | M1-D 只展示 M1 本地事件和链接；不实现 SSE Replay/Projector | 范围审计 | 无 |

## 范围

允许：统一 Controller、服务端本地 Principal 适配、只读聚合查询、确认命令审计、最小 Vue 页面、轮询、测试和证据。

禁止：修改 `DefaultAgentRuntime.run()`、跨源 WorkEvent Projector、统一 SSE Replay、聊天内 Multi-Agent 历史树、M3 命令控制、多实例 lease、复制 Runtime/Incident 大型页面逻辑。

## 实施结论

M1-D 已按本矩阵完成，证据见 `unified-agent-workbench-m1-d-evidence.md`。统一 Controller 采用服务端 Principal、三开关 fail-closed 注册、有界异步 route/dispatch；前端只展示 M1 本地事件和既有专项页面链接，没有提前实现 M2/M3。最终状态：`PASSED`。
