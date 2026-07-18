# Incident Command M1-C 同 childRunId 续跑门禁报告

> 结论：`PASSED`
>
> 日期：2026-07-18

## 门禁结论

M1-C 没有重写 `DefaultAgentRuntime.run()` 主循环，而是在 Runtime 边界增加显式输入检查点：

- Specialist/Reviewer 首轮可持久化为 `WAITING_INPUT` 后返回，线程、模型连接和进程内上下文均已释放；
- Follow-up 通过 `AgentContinuationStore` CAS claim 原 Run，仍使用同一个 `runId`；
- 原 Timeline、ToolResult、Profile、预算、deadline、Token 和成本累计，不创建伪“续跑 Run”；
- Follow-up 重新经过输入 Guardrail，最终回答继续经过输出 Guardrail；
- `COMPLETED / FAILED / BLOCKED` 等终态不能 reopen；
- 不需要追问时可把等待 Run 原子收口为 `COMPLETED`。

## 真实 PostgreSQL 自动化证据

测试类：

```text
AgentContinuationRuntimePostgresIT
```

覆盖：

1. 新建第二个 Runtime 实例模拟进程重启，从 PostgreSQL 读取原 Run 后同 runId 续跑；
2. 两个并发 continue 使用 version CAS，只有一个获得执行权；
3. 不追问时正常完成，完成后的 Run 不能再次打开。

执行命令：

```powershell
$env:INCIDENT_POSTGRES_IT = "true"
$env:AGENT_STORAGE_POSTGRES_PASSWORD = "1234"
mvn "-Dtest=AgentContinuationRuntimePostgresIT" test
```

结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

## 回归边界

整仓测试继续覆盖普通 Run、pause/resume、HITL、Guardrail、流式事件和原 OrderCare 恢复闭环。M1-C 只对显式调用 `runUntilInputCheckpoint` 的内部场景启用，普通用户请求不会自动进入 `WAITING_INPUT`。

