# Unified Agent Workbench 前端

> 文件名为历史兼容路径；当前前端已经不是旧“Vue 学习控制台”。`/` 是统一任务入口，Run 历史和事故页面降级为高级观测入口。

## 1. 页面入口

| 路由 | 用途 |
|---|---|
| `/` | Unified Agent Workbench，普通用户唯一任务入口 |
| `/workbench`、`/runtime` | 重定向到 `/` |
| `/runs` | Run 历史与回放，开发调试 |
| `/approvals` | 人工审批中心 |
| `/incident-command` | 事故调查高级视图 |
| `/capabilities` | Tool / Skill 能力地图 |
| `/knowledge` | RAG / Memory 实验室 |
| `/observability` | Trace / Eval / AgentOps |
| `/api-lab` | Controller 调试 |

## 2. 启动

后端必须启动在 `8083`，并开启 Workbench Web/Routing/Dispatch/Projection。详见 [构建与运行](build-and-run.md)。

```powershell
cd frontend
npm install
npm run dev
```

访问：`http://127.0.0.1:5173/`。Vite 将 `/api` 代理到 `http://localhost:8083`。

## 3. 当前页面结构

```text
App
└─ UnifiedWorkbench
   ├─ WorkbenchTaskSidebar
   │  ├─ 新建任务
   │  ├─ 搜索
   │  └─ Conversation / WorkItem 历史
   ├─ WorkbenchConversationPanel
   │  ├─ ConversationTurnSection
   │  ├─ ConversationItemRenderer
   │  └─ WorkbenchComposer
   └─ ExecutionInspector
      ├─ 概览
      ├─ 活动
      ├─ Agents
      ├─ 工具
      ├─ 证据
      └─ Diagnostics / EventPayloadDrawer
```

中间时间线按 WorkItem/Turn 展示用户可读内容；右侧 Inspector 展示技术事件。点击中间执行记录默认展开业务语义详情，“在检查器中打开”才定位原始技术事件。

## 4. 数据源

```text
agent_work_input / AgentConversationTurn
→ 用户消息

AgentWorkItem + RouteDecision
→ 标题、三维状态、任务理解与确认卡

PublicPresentation
→ 用户可读执行摘要、工具活动、Agent 调度、错误和最终结果

Primary Run MODEL_DELTA
→ 实时回答 buffer

WorkEvent + Runtime Event + Trace
→ Execution Inspector

Execution Tree + Incident Aggregate
→ Agents / Evidence / Conflict / Assessment
```

前端不维护第二套业务状态机。terminal Presentation/Runtime Event 到达后会重新读取权威 WorkItem Detail，使左侧、顶部和 Inspector 最终一致。

## 5. 流式回答

Workbench 同时使用：

- WorkItem 统一事件流：产品状态、路由、工具、审批和终态；
- PublicPresentation 流：安全用户可读内容；
- Primary Run Runtime Event：真实 `MODEL_DELTA`。

Live buffer 只接受 active Primary Run；Child Run delta 不拼入主回答。完成后以持久化最终消息校正 live buffer，只保留一条最终回答。断线重连使用复合 cursor 和 eventId 去重。

## 6. Markdown

最终回答使用：

- `marked`；
- `marked-highlight` + `highlight.js`；
- `DOMPurify`；
- Markdown normalizer。

支持标题、列表、引用、GFM 表格、代码块和复制。ToolCall Envelope、原始 Tool JSON 和 hidden reasoning 不进入最终回答。

## 7. Preview、Approval 和 Incident

Incident Scope Preview 展示：

- 绝对时间范围和时区；
- anomaly type、候选数量、requestId 和权威 queue；
- 数据源健康、截断提示和 Specialist 组合；
- 默认折叠的安全候选详情；
- 调整、确认启动和取消操作。

Assessment 仍显示在最终回答区域；Specialist 记录的业务详情可以在中间折叠展开，原始 payload 留在 Inspector。

## 8. 终止与后续输入

- Composer 在请求提交后立即切换为终止按钮，不等待第一个 delta；
- WorkItem `CANCEL_ACTIVE_WORK` 与 Runtime cancel 使用明确命令，不把文本“终止任务”当普通新目标；
- 已完成 WorkItem 后的普通追问会在同一 Conversation 中创建新的 WorkItem/Turn，并通过最近已完成轮次构造受控上下文；
- 切换 Turn 时，流式事件和折叠状态必须按 WorkItem 隔离，不能更新错误任务。

## 9. 前端验证

```powershell
cd frontend
npm test
npx.cmd vue-tsc -b
npm run build
```

`npm test` 当前运行 conversation projection、P3～P6、turn history 等 smoke 脚本。它是契约测试，不替代真实浏览器验收。

## 10. 已知边界

- 本地前端没有生产登录/租户切换；
- 浏览器人工视觉验收的历史证据并不覆盖每个后续提交；
- Run/Incident 高级页面仍存在，是调试视图，不应作为普通入口；
- Vite `ECONNRESET` 只表示 SSE 连接被关闭，业务根因要查 WorkItem、Run 和后端日志。
