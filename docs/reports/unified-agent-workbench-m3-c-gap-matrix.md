# Unified Agent Workbench M3-C Gap Matrix

更新时间：2026-07-20

| 链路 | M3-C 前事实 | M3-C 门禁 |
|---|---|---|
| Routing | stale 时间戳 + 有界 attempt；没有 owner、续租和旧 worker 隔离 | 每次 attempt 持久化 owner/lease/fencing；模型调用期间 heartbeat；过期接管递增 token；迟到终态写入拒绝 |
| Dispatch | 稳定 dispatchRequestId 和 Adapter reconciliation；无执行 owner | Adapter 调用前 claim；调用期间 heartbeat；接管只使用原 dispatchRequestId reconcile；旧 owner 不得写 WorkLink/终态 |
| WorkEvent Projector | cursor 与事件幂等，但多实例会重复读取同一来源 | cursor 行 claim + `FOR UPDATE SKIP LOCKED`；fenced append/advance；崩溃后从原 cursor 接管 |
| Incident Task | Phase 3 已有 lease、heartbeat、stale scan、fencing 和 Evidence 事务提交 | 不复制实现；纳入 Workbench 故障恢复门禁，验证旧 token 无 Evidence/Event、接管只发生一次 |
| Recovery Item | Phase 3 已有原 actionRequestId 对账和 fenced terminal write | 复用既有 Phase 3 PostgreSQL 与 Runtime E2E 证据，不新增批量写协议 |

## 不做

- 不修改 `DefaultAgentRuntime.run()` 主循环；
- 不把 PostgreSQL 产品投影顺序描述成跨 Store 的真实全局顺序；
- 不在旧 owner 失联时生成新的 dispatchRequestId、actionRequestId 或 Evidence idempotencyKey；
- 不提前实现 M3-D 的最终 Eval 包装与前端演示材料；
- 不执行 `git push`。
