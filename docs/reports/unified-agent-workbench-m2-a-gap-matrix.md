# Unified Agent Workbench M2-A 跨源 WorkEvent Projector 缺口矩阵

> 基线：M1-E `2c5701e`；范围仅限持久化跨源投影，不实现 M2-B SSE/Replay 或 M2-C 前端执行树。

| 冻结要求 | 当前事实 | M2-A 补齐方式 | 门禁 |
|---|---|---|---|
| 复用 M1 WorkEvent Schema/sequence | 表已含 sourceSequence、双时间和因果字段；仅支持 WORK_ITEM 本地 append | 新增内部 projected append，继续锁 `agent_work_item.next_event_sequence` 并在同一事务写事件 | 不迁移/重解释既有列 |
| Runtime 权威事件 | `agent_runtime_event` + run 内 sequence + afterSequence 查询已存在 | RUN link 驱动增量读取；跳过 `MODEL_DELTA`，投影结构化生命周期/工具/审批事件 | 重放 10 次只留一份 |
| Incident 权威事件 | `agent_task_event` + incident 内 eventSequence 已存在 | INCIDENT link 驱动增量读取并保留 taskId/childRunId/actor/因果字段 | 事件顺序与载荷不丢失 |
| Recovery Plan 权威事件 | Plan JSON 权威；现有 `RECOVERY_PLAN_CHANGED` 明确是 best-effort Incident 投影 | Plan Store 在 create/update 同一事务追加版本化快照事件；Projector 从该源读取 | Plan 更新与源事件原子提交 |
| 来源到 WorkItem 映射 | `agent_work_link` 已保存 RUN/INCIDENT/RECOVERY_PLAN | 内部投影源查询只读取已持久化 Link，不接受模型或 HTTP 指定 sourceId | 无孤立或越权映射 |
| 幂等与并发 sequence | 唯一键已冻结，但无跨源 append API | 先锁 WorkItem，再检查 `(work,sourceType,sourceId,sourceEventId)`；成功插入才递增 cursor | 多线程/多实例不重复、不跳号 |
| 子 Run | Incident Event 带 childRunId，但无 WorkLink | M2-A 先投影 Incident 权威事件；子 Run 展开留在 M2-C 执行树装配，不提前改变 Link 模型 | 不把 child delta 混入主回答 |
| 投影失败隔离 | 尚无 Projector | 单 source 失败记录日志并继续其他 source；不得修改 WorkItem/Run/Incident/Plan 业务状态 | 故障只造成同步延迟 |
| 多实例 claim | M2-A 要求并发安全，但 M3 才冻结租约恢复 | 不宣称 lease；依靠唯一键 + WorkItem 行锁保证并发提交正确性 | 并发投影测试通过 |

## 允许修改

- Recovery Plan Store 的兼容性事件表和事务写入；
- Workbench 内部投影源查询、跨源 append、Projector、配置和测试；
- `WorkEventType` 增加跨源投影类型；
- M2-A 证据与进度文档。

## 禁止修改

- `DefaultAgentRuntime.run()`；
- M1 `agent_work_event` 列、唯一键、`next_event_sequence` 起点和产品 sequence 语义；
- M2-B SSE/afterSequence API、MODEL_DELTA 实时通道；
- M2-C 前端 Multi-Agent 执行树；
- M3 WorkCommand、预算或 projector lease；
- 新 ExecutionTarget、通用消息总线或跨库分布式事务。
