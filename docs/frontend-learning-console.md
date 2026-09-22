# 采购 Unified Workbench 前端

当前业务入口为 /，/workbench 与 /runtime 保留兼容跳转到统一工作台。采购是核心业务；General 为受限通用执行能力。

| 页面 | 用途 |
| --- | --- |
| / | 采购对话、任务历史、审批、预算与执行详情 |
| /runs | Run 历史与回放 |
| /approvals | 审批检索与审计；当前 RFQ 可进入工作台处理 |
| /capabilities | Tool/Skill 能力查看 |
| /knowledge | RAG/Memory 实验室 |
| /observability | Trace、Eval、AgentOps |
| /api-lab | 调试接口 |
| /incident-command | 仅静态退役提示，不提供业务操作、不创建采购任务 |

UnifiedWorkbench、WorkbenchConversationPanel、ExecutionInspector 分别组织任务、对话与证据。通用 Trace 类型在 types/trace.ts；Workbench 仅保留最小历史字段，不依赖旧 Incident 领域文件。

WorkEvent/PublicPresentation 用于历史与可读活动；Primary Run MODEL_DELTA 用于流式回答。重连按 cursor 回放，终态与迟到 delta 安全覆盖必须保留。

旧目标、旧 Scope Preview、旧工具审批或 RETIRED 树，即使存量状态仍为 RUNNING/WAITING_APPROVAL，也不得显示继续输入、确认、恢复和执行按钮。历史内容与原始状态仍可读；退役审批不冒充采购 RFQ。

在 frontend 执行 npm run build（vue-tsc + Vite）及 npm test。七组 smoke 包括对话、传输、Markdown、Inspector、无障碍、历史及退役只读边界；SSR/静态 smoke 不替代真实浏览器或真实数据库验收。
