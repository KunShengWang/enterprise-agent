# Phase 1 提交前架构审计

日期：2026-10-02。分支：`feat/procurement-v2-erp`。
Source Baseline 与 Test Run HEAD：`b4e1189b6697733a63830d60cb8f422ebcd9ed2c`（`procurement-v0-baseline`）。测试在 dirty working tree 上执行，含未提交 Phase 1 实现和本轮修补；未生成新的 commit/tag。

## 1. Adapter Boundary

唯一运行时业务访问方向：Agent/既有 Provider → MCP → `ProcurementErpAdapter` → `ErpNextProcurementAdapter` → `ErpNextClient` → ERPNext。

| 位置 | 访问及职责 |
|---|---|
| `src/main/java/com/agent/platform/procurement/erp/ErpNextClient.java` | 唯一 ERP HTTP transport；GET/POST `/api/resource/`，鉴权、超时和错误转换；已收窄为 package-private |
| 同目录 `ErpNextProcurementAdapter.java` | 唯一生产 Client 调用方；Supplier、Item、Budget、关系、历史、绩效、Requisition、RFQ 的读取和 mapping；唯一 RFQ POST 位于 createRfq |
| 同目录 `ProcurementMcpServer.java` | composition root 构造具体 Adapter，交给 MCP 的接口；不进行 HTTP 调用 |
| `src/test/java/com/agent/platform/procurement/erp/ErpNextAdapterTests.java` | transport/mapping contract 测试：本地假 HTTP、错误/超时、模拟唯一约束冲突，不属于运行时旁路 |
| 同目录 `ErpNextProcurementIT.java` | 真实 Adapter/MCP 集成；唯一直接 Client 调用是按幂等键只读查询 RFQ 数量，独立验证 ERP 只有一条记录；写入走受控链 |
| `tools/erpnext/manage.ps1` | HTTP `/api/method/ping` 健康检查，无业务读写 |
| `tools/erpnext/seed.py`、`infra/erpnext/compose.yaml` | 明确的离线 bootstrap/reset、Frappe ORM、bench 安装；不是 Agent 运行时事实路径 |

Gateway/MCP bootstrap 原来仅为取环境变量而引用 Client，现改为 `RfqAuthorization.fromEnvironment()`，不再造成对 HTTP Client 的表面依赖。全仓扫描没有发现 MCP Tool 直接 HTTP 或 Controller 直写 ERP RFQ。扫描证据保存在 `target/erpnext/audit-access.txt`。

## 2. DTO Boundary

V2 Agent、application、domain、tool contract 使用 canonical records / 既有 SupplierCandidate、SupplierOffer、SupplierEvidence；Frappe JSON、DocType 字段解析仅在 ERP infrastructure/adapter 中。MCP 接收 JSON-RPC 参数不等于接收 ERP REST DTO。

修正 MCP 两个桥接方法中的硬编码 `ERPNext:Item` / `ERPNext:Procurement Item Supplier`，改为从 canonical Source 构建引用。Source.backend/entityType/entityId/version 仍携带可追溯来源标签，这是 opaque provenance，不是供领域层解析的 DocType 字段契约。

全仓检索还命中旧 `AwsSyntheticProcurementProvider` 的 `supplier_name` fixture 兼容字段。这是 V0 既有离线解析，不是 V2 ERP DTO 泄漏；ERP backend 不注册这个 Bean，未修改该 Legacy 行为。

## 3. RFQ Security

审计发现并修复：旧 ERP Gateway 对任意直接 create 调用都签名，只在注释里依赖上游已审批。现加入 `ErpRfqApprovalGuard`，从持久化治理 Store 回读审批、Run 和已领取的执行记录，复用原 `ProcurementRfqApprovalPreparer` 重建完整 11 字段并逐项比较，再核对 Gateway payload；不满足条件时签名前拒绝。exact request 比较先经过 JSON 序列化规范化，避免持久化后 Long→Integer 的等值整数被误判为篡改；受控链测试对 ApprovalRecord 做实际 JSON 往返后再执行。

原 Case 更新的 CAS、审批服务、Tool Runtime execution claim 不变。Handler 增加 canonical `RejectedRequestException` 分支，防止明确审批拒绝被旧 ERP 回执对账成成功。此异常不泄漏 ERP DTO。

| 场景 | 实际验证 |
|---|---|
| unauthorized | REQUESTED / 无 Approval / 无 execution claim 的 Gateway 请求拒绝；直接 Handler 调用拒绝；真实 MCP 完整未签名或伪造签名拒绝，ERP 查询该 key 无 RFQ |
| stale | 当前 Case version 1→2 后 Handler 与 Gateway 均拒绝；过期 Approval 拒绝；过期签名的真实 MCP 请求拒绝，ERP 无记录；签名到期时间不得超过 Approval.expiresAt 的单元测试通过 |
| payload tampering | approved quantity 20→21 在 Gateway 拒绝；签名 payload 修改 quantity 后真实 MCP 拒绝；创建后篡改重放也拒绝；同 key 不同 digest 的 Adapter contract 测试返回 conflict |
| duplicate | 真实受控创建后，相同 key/payload 重复调用返回同 RFQ ID；独立 ERP 查询 count=1；模拟唯一约束竞争的 contract 测试只 POST 一次并对账 |
| rejection reconciliation | 创建成功后将审批置为过期，再调用 Handler 得到 RFQ_REQUEST_REJECTED；验证不调用 findByIdempotencyKey，不能恢复成成功 |

测试辅助入口共用真实 Preparer、Runtime、Handler、Guard、Gateway；Approval/Run/Case/execution Store 为测试替身，真实 IT 的 MCP 子进程与 ERP 写入是真实的。未声称验证生产 PostgreSQL/Workbench 端到端。

安全结论覆盖受支持的应用入口。ERP 管理员、持有 API 写凭证/签名密钥的可信 infrastructure 本身属于信任边界；它不是抵御主机密钥泄漏的方案。Case 版本检查发生在签名前，MCP 验证短期授权（Gateway 最多 120 秒，且不晚于持久化审批到期时间）；没有实现跨治理 DB 与 ERP 的原子事务或已签发授权的即时撤销，不宣称消除了跨系统检查与写入间的竞争窗口。

## 4. Backend Fallback

补充 `ProcurementBackendEnvironment`，支持明确的 `procurement.backend` 及 `enterprise-agent.procurement.backend`。在 Spring Bean 条件选择前，erpnext 固定 provider=mcp、ERP Gateway/Profile、ERP tool prefix 并禁用 scenario。synthetic 显式选择 V0；旧无 backend 配置继续兼容。未知值和 backend 别名冲突启动失败，不暗中选 synthetic。

真实 Spring bootstrap 测试确认 erpnext 模式没有 AwsSyntheticProcurementProvider Bean。MCP unavailable / ERP_UNAVAILABLE / ERP_AUTH_ERROR / ERP_TIMEOUT 均抛出明确失败；transport contract 另以实际本地 HTTP 验证 401/403、timeout 和连接失败。没有 availability-based fallback。

## 5. System of Record / Provider Bridge

没有新增 supplier、item、history、performance、budget 运行时表/Repository/Store。新增八类 JSON 只供 bootstrap/test 使用；运行时 MCP/Adapter 不读取它们。ERP 保存采购事实；既有 Runtime DB 保存 Case、Run、审批、执行/幂等、Recommendation/Evidence/Trace 等治理记录。Evidence 中必要的观察快照不是第二套可维护的采购主数据。

- `search_suppliers`：既有 McpProcurementDataProvider 按精确 SKU，读取 Adapter 的商品、供应关系及获批供应商，转换为原候选协议。
- `get_offers`：同 Provider 读取商品、参考价格/交期/容量、部门预算和绩效，组装既有 offer/evidence 契约；未读取 Legacy JSON。

两者仍在内部 MCP discovery 中供 Java Provider 调用，但不在 Single Agent 白名单。测试使用实际 `DefaultAgentRuntime.capabilitiesFor` 过滤 14 个远端定义，模型只收到 11 个只读 ERP 工具；两个桥接 schema 和远端 RFQ 写工具均不进入模型工具上下文。业务读取结果可通过本地 Search/Finalize 进入模型上下文，内部桥接工具定义不进入。

## 6. Seed Determinism

连续执行两次 `pwsh -NoProfile -File tools/erpnext/manage.ps1 reset`；该命令内部为 reset→seed。每轮完成后，用只读 Frappe ORM 对所有 seed 业务键回读完整文档进行比较。

| 实体 | Reset/reseed 1 | Reset/reseed 2 |
|---|---:|---:|
| Supplier | 6 | 6 |
| Item | 10 | 10 |
| ItemSupplier | 18 | 18 |
| Department | 3 | 3 |
| Budget | 3 | 3 |
| PurchaseHistory | 24 | 24 |
| SupplierPerformance | 6 | 6 |
| Requisition | 6 | 6 |
| 演示 RFQ | 0 | 0 |

全部 76 个业务 ID 和回读字段值相同。规范化业务 JSON SHA-256 均为 `53bac40d9d29f26e1648877efd43f0b063bc3f0304910f25265228b1f211157e`。

比较排除 creation/modified/owner 等 ERP 审计元数据及子表内部随机 name，保留父业务 ID、关系、顺序、业务日期和业务值。原始 modified 另存以供追溯，两轮确实不同；不能将整个 ERP 响应或 Source.version 当作跨 reset 固定字节。历史采购日期、绩效 observation_date、需求 required_by、财年均为 seed 固定值。RFQ 是新交易，编号可变，应以审批幂等键关联，不作为 seed 的固定期望 ID。

重置前有 5 条带演示标记的 RFQ（含本轮安全 IT）；第一轮全部清理，第二轮仍为 0。清理限定演示标记和明确 seed keys，不删除站点/卷。两轮无需修改 seed/reset 实现。授权期限和审批 JSON 往返的各轮补测产生的测试 RFQ 均已额外 reset/reseed 清理；`audit-seed-final.json` 再次确认同一业务哈希及 0 条演示 RFQ。

证据：`target/erpnext/audit-reset-1.log`、`audit-reset-2.log`、`audit-seed-pre-reset.json`、`audit-seed-reset-1.json`、`audit-seed-reset-2.json`；只读审计脚本 `target/erpnext/audit_seed.py`。这些均为本地忽略证据，不属于 runtime store 或 Eval V2。

## 7. Tests

实际命令：

```powershell
mvn -B '-Dtest=ErpNextAdapterTests,ProcurementErpMcpTests,ErpNextRfqGatewayTests,ProcurementBackendBoundaryTests,ProcurementRfqHitlTests' test
pwsh -NoProfile -File tools/erpnext/manage.ps1 it
pwsh -NoProfile -File tools/erpnext/manage.ps1 reset
pwsh -NoProfile -File tools/erpnext/manage.ps1 reset
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

定向 43/43 通过；真实 ERP IT 3/3 通过，均 0 fail/error/skip。日志 `target/erpnext/audit-focused.log`、`audit-it.log`。最终 Legacy run：`v0-20261002-111106-aa6d9f99`，证据 `target/procurement-v0/v0-20261002-111106-aa6d9f99/`。

| 阶段 | total | pass | fail | error | skip |
|---|---:|---:|---:|---:|---:|
| procurement | 804 | 793 | 0 | 0 | 11 |
| replay | 380 | 380 | 0 | 0 | 0 |
| full | 1191 | 1182 | 0 | 0 | 9 |

构建 exit 0。Procurement 的 11 skip 包含 `JdbcProcurementCaseStorePostgresIT` 两项：未配置 PostgreSQL IT 专用环境，脚本默认 `PROCUREMENT_POSTGRES_IT=false`，没有使用 `-Postgres`；另 9 项为既有 artifact-driven 条件测试。默认全量 Java 不纳入 opt-in ERP/Live IT，其 9 skip 均为 artifact-driven 测试，不计作通过。

冻结 manifest 的 5 个 SHA-256 全部匹配，`audit-fingerprints.json` 保留核对结果。`git diff --check` 通过；新增文件也检查了行尾空白。最终工作区 35 个新文件、6 个 Phase 1 既有文件变更，另保留用户原 Handler 注释；暂存区为空。`target/erpnext/audit-git-status.txt` 保存实际 `git status --short`，`audit-file-hashes.json` 保存最终文件哈希。本地密钥、日志、snapshot 和生成文件均被 Git 忽略。


## 8. Scope / Review

Current Phase-1 Single-Agent demo: **28 tool calls**，沿用原实际 Demo 记录，本轮没有再调用模型、没有优化 Prompt、Agent Loop 或 Tool Loading。没有修改 Legacy grader、expected answers 或 frozen fixtures，没有运行 Live Eval。

没有开始 Eval V2、Harness、Multi-Agent、Context redesign。没有新增 PO、收货、发票、付款。

修改限于配置一致性、HTTP/DTO 边界、RFQ 授权防旁路、关键测试与说明。用户原 `ProcurementToolHandler.java` 注释未动，SHA-256 仍为 `8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D`。未暂存、commit、tag 或 push。等待人工 Review。
