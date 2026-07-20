# Unified Agent Workbench V1 — M1-D Evidence

> 日期：2026-07-20 CST
> 蓝图：V0.2.3 / FINAL
> 前置门禁：M1-D-S0 PASSED（`52de061`）

## 1. 交付结论

M1-D 完成最小统一产品入口：用户从一个自然语言输入框创建持久化 Input/WorkItem，系统异步执行命令分类、四目标路由和幂等派发；页面可查看 Focus、路由理由、Incident 不可变 Preview、M1 本地事件和目标链接。既有 Runtime、OrderCare 与 Incident 专项页面继续保留。

本阶段没有实现跨源 WorkEvent Projector、统一 SSE、聊天内 Multi-Agent 执行树或跨执行器暂停恢复；这些边界继续属于 M2/M3。

## 2. 服务端入口与身份边界

新增 `UnifiedWorkController`，提供：

- `POST /api/agent/conversations/{conversationId}/inputs`；
- Conversation 下 Input、WorkItem 与 Focus 查询；
- WorkItem 聚合详情与 M1 本地事件查询；
- Focus 切换；
- Incident route Preview 的确认和拒绝；
- abandon 以及 M1 不支持命令的结构化 409。

请求体不接收 userId、tenantId、roles、executionProfile 等可信字段。开发环境身份由服务端 `WorkbenchPrincipalProvider` 注入，所有查询继续向 Store 显式传递 `AuthenticatedPrincipal`，并使用 M1-D-S0 已验证的 SQL 级 tenant/principal 约束。

统一 API 只有在以下三个开关同时开启时才注册，防止“页面可提交但执行面关闭”导致 WorkItem 永久停在 ROUTING：

```powershell
$env:WORKBENCH_WEB_ENABLED = "true"
$env:WORKBENCH_ROUTING_ENABLED = "true"
$env:WORKBENCH_DISPATCH_ENABLED = "true"
```

自然语言命令仍然先落库并形成唯一 CommandDecision；由于 M1-D 尚未实现跨执行器 CommandHandler，命令返回 `UNSUPPORTED_FOR_TARGET` 结构化 409，且 `underlyingExecutionChanged=false`，不会伪造恢复成功。

## 3. 查询与异步派发

- `WorkbenchStore` 新增按 principal + conversation 限定的 Input/WorkItem 安全列表查询；
- `UnifiedWorkQueryService` 聚合 WorkItem、Focus、EFFECTIVE RoutingDecision、Preview、WorkLink 和本地 WorkEvent；
- `UnifiedWorkLauncher` 使用有界线程池执行 route/dispatch，HTTP 请求不持有模型调用线程；
- Incident 确认绑定 previewId、version、validatedInputDigest 与 scopeDigest，确认后才异步派发；
- Web、Routing、Dispatch 任一功能开关关闭时统一 Controller 不注册，保持 fail-closed。

## 4. 前端

新增 `/workbench` 页面和侧边栏入口，具备：

- 自然语言目标输入；
- Conversation 内 WorkItem 历史与 Focus；
- 路由目标、结构化理由和状态；
- Incident Preview 的明确确认/拒绝；
- M1 本地事件时间线；
- GENERAL_AGENT、ORDERCARE_CASE 的 Run 跳转；
- INCIDENT_INVESTIGATION 的 Incident 跳转；
- INCIDENT_RECOVERY_PLAN 通过路由校验输入解析 incidentId，并携带 planId 跳转。

`IncidentCommandView` 已支持从 URL 读取 `incidentId`，并按可选 `planId` 聚焦 Recovery Plan。统一页不复制 Runtime/Incident 大型展示逻辑。

## 5. 路由可靠性补丁

真实模型门禁发现 Incident 目录把互斥字段写成字面量 `batchId|requestIds`，模型可能把它原样作为一个 JSON key，导致完整输入被 Java Validator 判为缺少范围。目录协议已改为 `oneOf:batchId,requestIds`，与 OrderCare 的 oneOf 表达一致。

该修复没有放宽 Validator：缺失 scope、queue 或来源不可信时仍 `REQUIRE_CLARIFICATION`；输入完整时仍只能 `REQUIRE_CONFIRMATION`，不能由模型 confidence 静默启动 Incident。

## 6. 自动化证据

### 6.1 Web 与应用单元

```powershell
mvn.cmd '-Dtest=UnifiedWorkControllerTests,WorkbenchModelAndServiceTests,M1BRoutingUnitTests,ExecutionAdapterUnitTests' test
```

结果：16 tests，0 failures，0 errors。覆盖请求 metadata 越权拒绝、按钮命令审计、自然语言命令结构化 409、四目标注册与 Adapter 边界。

### 6.2 PostgreSQL

```powershell
$env:WORKBENCH_POSTGRES_IT = 'true'
mvn.cmd '-Dtest=*PostgresIT' test
```

结果：45 tests，0 failures，0 errors；其中 11 条为既有、未启用其专属环境的外部设施测试。Workbench 相关 34/34 通过，新增 `UnifiedWorkbenchControllerPostgresIT` 证明 Input 先落库、WorkItem/Focus/首事件原子可见、Launcher 接收稳定 workItemId/routingRequestId，并可从统一查询 API 回查。

### 6.3 真实模型

```powershell
$env:WORKBENCH_REAL_MODEL_IT = 'true'
mvn.cmd '-Dtest=M1BRealModelRoutingIT' test
```

结果：3/3，0 failures，0 errors。覆盖自然语言命令、General 路由和完整 Incident 输入必须进入 Java 明确确认门禁。

### 6.4 全量与前端

```powershell
mvn.cmd clean test
cd frontend
npm.cmd run build
```

结果：后端 159 tests，0 failures，0 errors，11 skipped；前端 `vue-tsc -b` 与 Vite production build 通过。11 个 skipped 与基线一致，均为显式环境门禁。

## 7. 真实页面验证

在本地 PostgreSQL、8083 后端和 5173 前端上完成浏览器验证：

1. 统一工作台提交“解释 Java CAS”目标；
2. 页面依次展示 WORK_ITEM_CREATED、ROUTING_STARTED、ROUTING_DECIDED、DISPATCH_READY、DISPATCH_STARTED、EXECUTION_DISPATCHED；
3. Router 选择 `GENERAL_AGENT` 并展示审计理由；
4. WorkItem 进入 `DISPATCHED`，生成唯一 Run link；
5. 点击链接后 URL 携带同一个 runId，专项 Runtime 页面加载该 RunRecord。

临时验证后已停止临时后端并清理 Workbench 测试数据。专项 Runtime 在该临时进程中进入自身 FAILED 终态，但不影响本门禁验证的路由、幂等派发、WorkLink 和正确页面定位；Runtime 的成功业务回归由既有测试覆盖。

## 8. 范围审计

- `DefaultAgentRuntime.run()`：未修改；
- 旧 `/api/agent/runs/**`、OrderCare、Incident 入口：保留；
- 新 ExecutionTarget：未增加；
- 未通过本机 HTTP 反调既有 Controller；
- 未实现 M2 Projector/SSE/执行树；
- 未实现 M3 跨执行器命令；
- `git diff --check`：通过；
- push：未执行。

最终判定：`M1-D PASSED`。Checkpoint 为本报告所在的本地提交。
