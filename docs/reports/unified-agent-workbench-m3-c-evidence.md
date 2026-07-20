# Unified Agent Workbench M3-C Evidence

更新时间：2026-07-20 CST

## 结论

M3-C 多实例与故障恢复门禁：**PASSED**。

控制面的 Routing、Dispatch 和 WorkEvent Projector 已从“时间戳扫描 + 幂等结果”升级为持久化 claim/lease/fencing 协议。Incident Task 和 Recovery Item 继续复用 Incident Command Phase 3 的权威租约内核，不建立第二套任务状态机。

## 接管协议

```text
数据库 claim
-> 持久化 leaseOwner / leaseUntil / fencingToken
-> 执行期间 heartbeat
-> terminal write 同事务校验 owner + token + lease
-> owner 崩溃且 lease 过期
-> 新 owner claim，token 单调递增
-> 旧 owner 的迟到写入被 CAS/fencing 拒绝
```

- Routing 接管会把旧 STARTED attempt 标记为 `RESULT_UNKNOWN`，保留未知 Token 上界，并使用原 `routingRequestId` 创建有界新 attempt。
- Dispatch 接管只使用原 `dispatchRequestId` 调用 Adapter reconciliation；目标已创建时只补 WorkLink，不创建第二目标。
- Projector 对 cursor 行使用 `FOR UPDATE SKIP LOCKED`，事件 append 与 cursor advance 均校验同一 claim；接管后从持久化 cursor 继续。
- Incident Task 的 Specialist 结果提交继续保证 Task CAS、Evidence append、TaskEvent sequence 和状态推进同一 PostgreSQL 事务；旧 fencing token 不产生 Evidence 或 Event。
- Recovery Item 接管继续对账原 `actionRequestId`，不能换键盲重试副作用。

## 故障门禁

专项 PostgreSQL 用例覆盖：

1. 两个 Projector owner 竞争同一来源时只有一个取得 claim；
2. Projector lease 过期后 token 从 1 递增到 2，旧 owner advance 被拒绝；
3. Routing 活跃 lease 阻止第二 owner，过期后单次接管，旧 owner fail/complete 被拒绝；
4. Dispatch 活跃 lease 阻止第二 owner，过期后使用原 dispatchRequestId reconciliation，旧 owner 被拒绝；
5. Adapter 已创建目标但 WorkLink 未提交时，恢复后 target count 仍为 1；
6. Incident Task 过期接管后旧 token 提交不产生 Evidence/Event；
7. Recovery Item 双 owner 竞争、旧 token 拒绝和原 actionRequestId 对账；
8. Projector 重启后从 cursor 补齐，重复回放不增加 WorkEvent。

## 配置

```text
WORKBENCH_ROUTING_LEASE_MILLIS
WORKBENCH_DISPATCH_LEASE_MILLIS
WORKBENCH_PROJECTION_LEASE_MILLIS
WORKBENCH_INSTANCE_ID
```

同时修正 `application.yaml` 中 `workbench.stream` 与 `workbench.budget` 的层级，使其与各自 `@ConfigurationProperties` 前缀一致。

## 自动化结果

- M3-C Routing/Dispatch/Projector 专项 PostgreSQL：12/12；
- 全部 PostgreSQL IT：70 条，实际执行 58，12 条既有外部环境跳过，0 failure/error；
- 全量后端：206 条，0 failure/error，11 条既有环境跳过；
- 前端 `vue-tsc -b` 与 Vite production build：通过。

## 范围约束

- 未修改 `DefaultAgentRuntime.run()`；
- 未改变 Incident 业务状态机、Evidence Schema 或 Recovery Action 幂等键；
- 未宣称外部 MQ、FlowOrder 或模型供应商具备 exactly-once；
- 本地 checkpoint 不推送。
