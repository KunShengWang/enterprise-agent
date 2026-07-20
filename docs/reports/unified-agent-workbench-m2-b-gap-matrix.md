# Unified Agent Workbench M2-B 统一 SSE / Replay 缺口矩阵

> 基线：M2-A `30c7b9f`；范围仅限可靠事件传输和主回答增量，不实现 M2-C Multi-Agent 执行树。

| 冻结要求 | 当前事实 | M2-B 补齐方式 | 门禁 |
|---|---|---|---|
| WorkItem 统一 SSE | 只有 JSON `events?afterSequence` | 新增 `events/stream`，从同一 `agent_work_event` 查询读取 | SSE/历史 eventId+sequence 一致 |
| afterSequence Replay | Store 已支持，页面未使用 | 连接建立先补拉 cursor 后事件，再进入轮询 | 断线期间事件全部补齐 |
| Last-Event-ID | 无统一协议 | SSE id 编码 `workSequence + primaryRunSequence`，服务端同时接受 query cursor 和 header | 重连不依赖内存会话 |
| gap detection | 无 | 服务端检测产品 sequence 非连续并发 GAP；前端停止盲追加、回拉历史 | 不静默越过缺口 |
| eventId 去重 | 数据库有唯一键，前端无去重 | 前端按 eventId/sourceEventId 去重，cursor 只单调前进 | 重放不重复卡片/正文 |
| MODEL_DELTA 双通道 | Runtime timeline 已持久化，M2-A 明确不投影 delta | SSE 同时轮询直接 PRIMARY RUN 的 timeline；结构化状态继续走 WorkEvent | token 不写 WorkEvent |
| 子 Agent delta 隔离 | Incident childRunId 仅在 Incident Event 中，无直接 WorkLink | 只允许 WorkItem 的 `PRIMARY RUN` Link 输出正文 delta；Incident/Planner child Run 不进入正文通道 | 子 Agent delta 混入数为 0 |
| 心跳与背压 | Incident SSE 有简单 heartbeat | 有界批量、串行 poll、heartbeat、SSE error 后客户端 cursor 重连 | 不启动第二个底层执行 |
| 身份与所有权 | JSON 查询已 principal-scoped | SSE 建连和每轮读取沿用 `AuthenticatedPrincipal` 与 WorkLink 所有权 | 跨租户不可订阅 |

## 允许修改

- Workbench SSE DTO、cursor、stream service 和 Controller 只读端点；
- Unified Workbench 的 EventSource、去重、gap recovery 和主回答增量展示；
- M2-B 测试、配置、证据和进度文档。

## 禁止修改

- `DefaultAgentRuntime.run()`；
- `agent_work_event` Schema、M1/M2-A sequence 和 projector 语义；
- 将 MODEL_DELTA 逐条复制进 WorkEvent；
- 订阅 Incident childRun/Recovery plannerRun 作为主正文；
- 因 SSE 重连重新 dispatch、resume 或创建 Run；
- M2-C Agent 树、角色 Attempt 聚合或 M3 命令/预算/lease。
