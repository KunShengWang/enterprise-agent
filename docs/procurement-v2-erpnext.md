# Procurement V2：ERPNext 企业数据环境（Phase 1）

## 角色与数据链路

ERPNext 是企业采购事实的 System of Record。本项目继续保存 Case、Run、Recommendation、Evidence 引用、审批、执行回执、Trace 与幂等治理状态，不增加第二套供应商或商品主数据表。

```text
离线 synthetic enterprise seed → ERPNext
当前 Single Procurement Agent → 既有 Tool Runtime / MCP Gateway
  → Procurement MCP → ProcurementErpAdapter → ErpNextProcurementAdapter → ERPNext REST
本地 Search / Finalize → 既有 McpProcurementDataProvider → 同一 MCP / Adapter / ERPNext
```

数据仅涉及 AI Workstation。Seed 是虚构企业事实，没有推荐答案。运行时 Adapter 与 MCP 不读取 seed 文件或 Legacy scenario；只有导入工具读取 seed。ERP 不可用时返回明确错误，绝不回退 JSON。

V0 保留 `AwsSyntheticProcurementProvider`、`SimulatedProcurementRfqGateway`、冻结 fixture 和 Legacy 回归入口。V2 显式启用 `application-erpnext.yaml`：`backend=erpnext`、`provider=mcp`、`mcp-tool-prefix=mcp.erpnext.`、`erpnext.enabled=true`，并将 Legacy scenario 路径设为不存在。V2 在原 Profile 中关闭两个 Specialist capability，继续使用现有单 Agent Runtime。

ERP Profile 保持有限预算：24 turns、20 model calls、40 tool calls、累计输入 384,000 tokens、输出 16,000 tokens、240 秒。ERP 配置将既有工具结果展示上限设为 24,000 字符，以保留包含绩效/来源版本的完整 Search Evidence 引用；没有修改 Context/Runtime 实现或 Legacy Profile。预算是累计调用开销，并非上下文窗口大小。

## 官方环境与启动

采用 [frappe_docker 官方开发示例](https://github.com/frappe/frappe_docker/blob/main/pwd.yml) 的服务拓扑，固定 ERPNext 镜像 `frappe/erpnext:v16.37.0`、MariaDB 11.8、Redis 6.2。这里是本地开发环境，不是生产部署方案，不修改 ERPNext 源码。

前提：Docker Desktop 已启动、PowerShell 7、Java 17、Maven。仓库根目录执行：

```powershell
pwsh -NoProfile -File tools/erpnext/manage.ps1 start
pwsh -NoProfile -File tools/erpnext/manage.ps1 health
pwsh -NoProfile -File tools/erpnext/manage.ps1 seed
pwsh -NoProfile -File tools/erpnext/manage.ps1 prepare
```

首次启动需要下载镜像并安装站点。`create-site` 容器必须退出且 exit code 为 0，再进行 seed；`health` 验证该状态及 HTTP `pong`。浏览器地址默认为 `http://localhost:8088`，仅绑定 localhost。站点名固定 `frontend`。

`start` 在不存在本地配置时生成随机 DB/admin 密码到被忽略的 `infra/erpnext/.env`。如需自定义端口，可以使用 `.env.example` 创建 `.env` 并填写本地值。不要提交 `.env`，不要在工单或日志贴出密码。站点初始化的 Administrator 密码在这个本地文件中。

Compose 项目名 `procurement-erpnext`；数据库、sites、logs、Redis 使用项目专用 named volumes。普通 `docker compose --env-file infra/erpnext/.env -f infra/erpnext/compose.yaml down` 保留数据卷。重新 `start` 复用已有站点；本流程不执行 `down -v`。

如果 Compose 并发拉取同一大镜像层缓慢，可先单独执行 `docker pull frappe/erpnext:v16.37.0`，完成后再次 `start`。不要用删除数据卷解决下载问题。

## Seed 与 reset

```powershell
pwsh -NoProfile -File tools/erpnext/manage.ps1 seed   # 稳定 ID upsert
pwsh -NoProfile -File tools/erpnext/manage.ps1 reset  # 仅本演示数据删除并重建
```

`seed.py` 在 ERPNext 容器内调用 Frappe 支持的 ORM/custom field/DocType API。它建立公司 Synthetic AI Labs（SAL）、2026 财年、3 个部门及成本中心，再导入事实。站点时区固定 UTC，Adapter 将 ERP `modified` 解释为 UTC snapshot/version。错误报告包含当前 DocType/business key。

Seed 文件与目标规模：

| 文件 | 目标数量 | 职责 |
|---|---:|---|
| `suppliers.json` | 6 | 虚构名称、Approved/Inactive、容量等级 |
| `items.json` | 10 | 精确 SKU、显存、质保、单位 |
| `item_suppliers.json` | 18 | 支持 SKU、参考价格、交期、数量容量 |
| `departments.json` | 3 | 部门与成本中心业务键 |
| `budgets.json` | 3 | 已分配、已承诺、币种与财年 |
| `purchase_history.json` | 24 | 历史采购观察记录 |
| `supplier_performance.json` | 6 | 历史准时率、质量、交易次数快照 |
| `sample_requisitions.json` | 6 | 部门、商品、数量、需求日期 |

稳定 ID 例如 `V2-SUP-001`、`V2-WS-001`、`V2-REQ-001`。同名导入更新事实，不无限复制；已存在的示例 Material Request 保留用户编辑。Reset 只清理演示标记 RFQ 及 seed 明确列出的业务键，然后重建，不删除站点、公司或其他业务数据。后续正式 Eval reset 不在此阶段实现。

绩效为 synthetic 导入快照，并非从这 24 条示例记录自动计算。采购历史不是本阶段新建的 PO/Receipt/Invoice。价格是 ERP reference price，不是供应商正式报价。6 家供应商在价格、交期、准时率、质量、容量、支持 SKU、状态上存在差异，不存储 winner/recommendation/oracle。

成功导入的实际数量写入被忽略的 `target/erpnext/seed-result.json`；每个业务键经 ERP 查询确认存在后才输出数量。API 用户 `procurement-demo@example.invalid` 只授予事实读取与 draft RFQ create 权限。API token 与 RFQ 签名密钥写入被忽略的 `target/erpnext/runtime.env`，不输出 stdout。

## Canonical mapping

| Canonical model | ERPNext DocType / 字段 |
|---|---|
| Supplier | 原生 Supplier；custom approval/capacity，disabled |
| Item | 原生 Item；item_group、stock_uom、custom GPU/warranty |
| ItemSupplier | custom `Procurement Item Supplier`；Item/Supplier Link、reference_price、currency、lead_time_days、capacity_units |
| Department / Cost Center | 原生 Department / Cost Center；稳定 V2 部门键 |
| BudgetStatus | 原生 Budget v16：account、budget_amount、from/to_fiscal_year；custom_department_id、custom_committed_amount、custom_currency |
| PurchaseHistory | custom `Procurement Purchase History`；只读历史 projection |
| SupplierPerformance | custom `Procurement Supplier Performance`；只读聚合观察快照 |
| Requisition | 原生 Material Request（Purchase 类型），custom_department_id |
| RfqRecord | 原生 Request for Quotation 草稿；custom_procurement_key 唯一索引、custom_request_digest |

原生 schema 参考 [ERPNext v16.37.0](https://github.com/frappe/erpnext/tree/v16.37.0/erpnext)。这一版明确仅支持一个 Requisition/RFQ 对应一个 Item，RFQ 对应一个 Supplier；遇到多行记录明确报错，不静默取第一行。预算仅支持一个部门/公司当前演示预算，多个匹配返回 conflict，不任意挑选。

每个 Canonical record 包含 `Source(backend, entityType, entityId, retrievedAt, version)`。现有 Provider 用 Java 重建 OFFER/Evidence digest；OFFER specifications 包含绩效、容量、预算及相关版本。Finalizer 继续使用既有证据校验，不接受模型编造的事实。

Adapter 错误：`ERP_AUTH_ERROR`、`ERP_NOT_FOUND`、`ERP_VALIDATION_ERROR`、`ERP_CONFLICT`、`ERP_TIMEOUT`、`ERP_UNAVAILABLE`、`ERP_RESPONSE_INVALID`。HTTP 默认 15 秒超时，不重定向，不泄漏 ERP 原始异常体；Decimal 保持十进制精度。分页按 name 排序，limit 1..200，返回 `nextOffset`，空结果正常结束。

Frappe 的唯一字段冲突 `UniqueValidationError` 即使以 HTTP 417 返回，也映射为 `ERP_CONFLICT`；与普通字段校验错误区分，允许按稳定 key 对账。

## MCP contract

独立 stdio JSON-RPC MCP 服务：`ProcurementMcpServer`。`tools/list` 返回每个工具的 description、inputSchema、outputSchema、read/write annotations；`tools/call` 成功提供 `structuredContent` 和 text JSON，领域错误提供 `isError=true` 与 `error.code`。单记录输出 `{record:...}`；分页输出 `{records:[...],nextOffset:...}`。输入拒绝未知字段、错误类型、越界与缺失参数。

| 领域工具 | 输入要点 | 性质 |
|---|---|---|
| procurement_get_requisition | id | read |
| procurement_list_requisitions | offset/limit | read |
| procurement_get_item | id | read |
| procurement_list_items | offset/limit | read |
| procurement_get_budget_status | departmentId | read |
| procurement_list_suppliers | offset/limit | read |
| procurement_get_supplier | id | read |
| procurement_list_item_suppliers | itemId, offset/limit | read |
| procurement_get_purchase_history | supplierId, offset/limit | read |
| procurement_get_supplier_performance | supplierId | read |
| procurement_get_rfq | id 或 idempotencyKey，二选一 | read |
| procurement_create_rfq | request, expiresAt, signature | 后端受控 write |

另有既有 Provider 协议的 `search_suppliers`、`get_offers` 两个内部桥接工具，模型白名单不包含它们。没有增加第二套 Provider hierarchy。MCP 处理层仅依赖 Canonical Adapter，不直接调用 HttpClient。

## RFQ 受控链路

```text
Agent 本地 procurement_create_rfq({}) 意图
→ 原有 ProcurementRfqApprovalPreparer 从 Case/本 Run Finalize 重建 11 字段
→ 原有 HITL / ApprovalRecord 绑定
→ 原有批准恢复、执行 claim、Case version 校验
→ ErpNextProcurementRfqGateway 再检查持久化 Approval / execution claim / 当前 Case 与 Finalize
→ 对 Canonical RFQ request + 短期有效时间签 HMAC
→ MCP 验证签名 → Adapter → ERPNext draft RFQ
```

远端 `mcp.erpnext.procurement_create_rfq` 不在模型白名单。签名密钥不进入模型上下文。RFQ request 必须使用确切 ERP SKU；为保持原有审批字段不变，V2 Case `productDescription` 存放 SKU id，不做模糊文本匹配。

Gateway 将 ERP `Draft` 映射为既有执行回执 `CREATED`，含义仅为草稿存在；不 submit、不发邮件。其他状态不伪装成成功。Adapter 写入前验证供应商批准状态、SKU、关系、容量、币种与交期；本阶段不预留预算/库存。

`rfq:<approvalId>` 映射到 ERP 唯一 custom key。相同 key/payload 读回已有 RFQ；同 key 不同 digest 返回 conflict。唯一索引处理并发竞争，冲突后查询核对；POST 超时不盲重试，交给已有 uncertain reconciliation。RFQ 不产生采购订单、收货、发票或付款。

## 应用与可重复 Demo

生产应用本身仍需要仓库既有 Agent Storage/PostgreSQL、模型等本地配置；ERPNext Compose 只提供 ERP 环境。

```powershell
pwsh -NoProfile -File tools/erpnext/manage.ps1 prepare
pwsh -NoProfile -File tools/erpnext/manage.ps1 app
```

这会加载本地 runtime.env 并以 Spring `erpnext` profile 启动现有应用。部门通过 MCP 进程环境 `ERPNEXT_DEPARTMENT` 指定，默认 `V2-ALG`；第一版一个部署对应一个演示部门，不是多租户部门授权系统。

演示提问：

> 算法部门 V2-ALG 计划采购 20 台 AI 工作站，预算 30 万人民币，21 天内交付，GPU 显存至少 24GB。请查询商品、预算、供应关系、供应商、历史采购和绩效，推荐历史交付稳定的供应商并解释权衡。本次只推荐，不创建 RFQ。

确定推荐后，如用户明确要求 RFQ，由现有工作台审批并恢复。不能从命令行直接伪造 LLM 审批。

独立真实模型 smoke demo（需要进程环境 `DEEPSEEK_API_KEY`，会调用模型 API）：

```powershell
pwsh -NoProfile -File tools/erpnext/manage.ps1 demo
```

该 smoke 复用现有 DefaultAgentRuntime、ContextManager、Tool Runtime 和 test-only 内存治理 Store，只将业务事实接到真实 ERP。结果与工具轨迹写入 `target/erpnext/agent-demo.json`。它是集成 smoke，不是 Eval V2，没有 grader 或 winner oracle；不会运行旧 Live Eval，不验证生产数据库持久化或 Workbench UI。

## 测试命令与证据

```powershell
mvn -B '-Dtest=ErpNextAdapterTests,ProcurementErpMcpTests,ErpNextRfqGatewayTests' test
pwsh -NoProfile -File tools/erpnext/manage.ps1 it
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

定向测试使用 HTTP contract fixtures / mock Adapter；真实 IT 必须显式 `ERPNEXT_IT=true` 并存在已 seed 的 ERP，未配置时 skip，不计作通过。真实 IT 使用运行时 API 身份、真实 child stdio MCP，读取七类事实并验证受控 RFQ 草稿唯一性与未签名调用拒绝。测试中的审批决定及治理 Store 为测试内存对象，ERP 业务写入为真实。

Legacy 脚本自带 fixture SHA-256 校验，执行 Procurement、Replay、全量 Java、构建，将每次报告保存在 `target/procurement-v0/<run-id>/`。不修改 V0 fingerprint manifest，不重跑或调整原 Live Eval 0/4 评分。

## 文件职责

- `infra/erpnext/compose.yaml`、`.env.example`：本地官方 ERP 服务及配置示例。
- `data/procurement/v2/seeds/*.json`：8 类企业事实。
- `tools/erpnext/generate_seed.py`：确定性离线生成；`seed.py`：导入/重置；`manage.ps1`：操作入口；`mcp-server.ps1`：生产 stdio 启动。
- `procurement/erp/ProcurementErpAdapter.java`：Canonical contract；`ErpNextClient.java`、`ErpNextProcurementAdapter.java`、`ProcurementErpException.java`：传输、mapping、错误。
- `procurement/erp/ProcurementMcpService.java`、`ProcurementMcpServer.java`：MCP 领域服务和 stdio。
- `procurement/erp/RfqAuthorization.java`、`ErpNextProcurementRfqGateway.java`：后端签名与现有 RFQ Gateway 接口适配。
- `ProcurementSourcingExecutionProfileFactory.java`、`SimulatedProcurementRfqGateway.java`、`application-erpnext.yaml`：显式模式切换，保留 Legacy。
- `mcp/StdioMcpToolGateway.java`：仅给生产构造器增加 `@Autowired`，修复多个构造器下启用 MCP 的 Spring 装配失败；不改变工具执行逻辑。
- `ErpNextAdapterTests`、`ProcurementErpMcpTests`、`ErpNextRfqGatewayTests`、`ErpRfqControlledTestSupport`：contract、安全链与 seed 检查。
- `ErpNextProcurementIT`、`ErpNextSingleAgentDemoIT`：真实 ERP IT 和独立 Single Agent smoke。
- `.gitignore`：忽略本地 ERP secret 与 Python cache。
- `docs/procurement-v2-erpnext.md`：启动、映射、contract、Demo、实际测试证据与范围说明。

用户原有 `ProcurementToolHandler.java` 注释修改不属于本阶段，保留其原字节，不纳入 Phase 1 改动说明。

## 明确边界

未实现 Supervisor、子 Agent、Agent-as-Tool、新 Harness/Runtime、Context/Memory 重构、Eval V2、Workbench V2、Web Search、Email、PO、收货、发票、付款。本阶段停止于企业事实环境与现有 Single Agent 的兼容接入，等待人工 Review，不自动提交或开始下一阶段。

## 初次 Phase 1 交付运行记录（架构审计前）

2026-10-02，Windows / PowerShell 7 / Java 17 / Maven 3.9.12：

- Source Baseline：`procurement-v0-baseline` → `b4e1189b6697733a63830d60cb8f422ebcd9ed2c`。
- Test Run HEAD：同上；分支 `feat/procurement-v2-erp`；Working Tree **dirty**，Phase 1 修改未提交，另保留用户原有 Handler 注释。本报告不声称存在 Phase 1 最终 commit SHA。
- 实际 ERPNext 16.37.0 / Frappe 16.36.0 已安装；`create-site` exit 0，HTTP readiness `pong`。重复 `start` 后仍通过 health。
- 首次 seed、重复 seed、reset/reseed 三次均实际完成：**Supplier 6、Item 10、ItemSupplier 18、Department 3、Budget 3、PurchaseHistory 24、Performance 6、Requisition 6**。不是仅检查 JSON 文件数量。
- 生产 `mcp-server.ps1` 入口已验证 initialize 与真实 Supplier 读取。
- 最后一次真实模型 Demo：`COMPLETED`，19 次 model call、28 次 tool call、280,659 累计输入 tokens，**0 个工具错误**。读取 ERP 事实，推荐 `V2-SUP-001`，价格 12,800 CNY × 20 = 256,000 CNY，交期 12 天，准时率 0.98；没有创建 RFQ。推荐 id 未作为 seed 答案或测试 expected winner。
- 独立受控 RFQ IT 经过实际 Preparer / Tool Runtime / Handler / Gateway / MCP / Adapter，真实创建 ERP draft，重复 key 查询确认仅 1 个业务记录；未批准、过期/伪造签名、Case 版本过期等边界分别有覆盖。

本地证据（均在忽略目录，不提交 secret 或生成物）：

- `target/erpnext/seed-first.log`、`seed-repeat.log`、`reset.log`、`seed-result.json`。
- `target/erpnext/health-final.log`、`stdio-wrapper.jsonl`。
- `target/erpnext/focused-final.log`：定向测试 **18 pass / 0 fail / 0 skip**。
- `target/erpnext/it-final.log`：真实 ERP IT **3 pass / 0 fail / 0 skip**。
- `target/erpnext/agent-demo.log`、`agent-demo.json`：真实模型 smoke **1 pass / 0 fail / 0 skip**。
- `target/erpnext/provider-demo.json`、`rfq-demo.json`：真实 Provider 事实与 RFQ 回读。

最后一次确定性回归：`pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite`，run id **`v0-20261002-100922-0091531a`**。原始命令、环境、XML、汇总位于 `target/procurement-v0/v0-20261002-100922-0091531a/`，脚本实际包含全量 `mvn test` 和构建。

| 阶段 | total | pass | fail | error | skip |
|---|---:|---:|---:|---:|---:|
| Legacy Procurement | 802 | 791 | 0 | 0 | 11 |
| Legacy Replay | 380 | 380 | 0 | 0 | 0 |
| Full Java | 1188 | 1179 | 0 | 0 | 9 |

构建 exit 0。Procurement 的 11 个 skip 包含既有 `JdbcProcurementCaseStorePostgresIT` 两个环境门控测试：本轮没有配置 PostgreSQL IT 专用环境，也未启用 `-Postgres`（脚本设置 `PROCUREMENT_POSTGRES_IT=false`）；另外 9 个为既有 artifact-driven 条件测试。独立 `-Dtest=*IT` 入口的 ERP 测试与真实模型 smoke 已按上述命令实际执行，不把它们冒充成默认全量测试中的结果。

冻结 manifest 的 **5 个资源 SHA-256 全部一致**；V0 grader、expected answers、Legacy fixture 未修改。旧 Live Eval 未重跑，仍为历史 business decision 4/4、grader 0/4 的 productCategory exact-match mismatch，不与本次 ERP smoke 混淆。

最终 Git：仅 Phase 1 的 30 个新文件、4 个既有文件变更，另有用户先前的 Handler 注释；没有 staged 文件，没有 commit/push。Handler 文件 SHA-256 保持 `8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D`。`git diff --check` 通过；本地 `.env`、`runtime.env`、日志及输出证据均被忽略。

历史失败未隐去：首轮全量测试与 ERP 初始化同时运行，既有 `StdioMcpToolGatewayTests` 出现启动超时（1 failure / 4 errors）；后续原测试不修改即重跑通过。首轮 Demo 在累计 token 预算耗尽时失败；一次中间 Demo 完成推荐但存在 Evidence 重试和文字比较错误，轨迹保留在 `agent-demo-budget-failure.*` / `agent-demo-first-completed.*`。最终只在 ERP 模式增加预算、完整工具结果展示及事实边界说明。正式 Spring Profile 验证还发现既有 MCP 多构造器装配问题，已以一个注解修复并增加装配测试。Smoke 原先限定必须调用单条 Supplier getter，实际模型采用包含相同事实的 Supplier list，因此检查改为接受这两种合法读取；没有修改 Legacy grader 或 benchmark。

未覆盖生产 PostgreSQL 治理存储与 Workbench 浏览器端的端到端操作；上述真实模型 smoke 的治理 Store 使用既有 test-only 内存实现。真实模型运行仍具有非确定性，需要人工复核最终文字，不等同正式 Eval 或生产验收。当前明确限制为单部门部署、单行 Requisition/RFQ 和 draft RFQ，不预留预算/库存。

## Phase 1 提交前架构审计

2026-10-02 的补充审计见 [完整审计报告](procurement-phase1-architecture-audit.md)。本轮不运行模型 Demo、不优化其 28 次工具调用。

新增显式 `procurement.backend=erpnext|synthetic` 配置入口（也支持 `enterprise-agent.procurement.backend`）。Spring 环境处理器在 Bean 条件判断前统一 Provider、Gateway 和 Profile 模式；ERP 模式固定 MCP 路径并禁用 Legacy scenario。未知值或两个 backend 配置冲突直接失败。显式 backend 优先于旧的 provider/enabled 设置；未设置 backend 时保留原 Legacy / generic MCP 配置，旧的 erpnext.enabled=true 也会统一成 ERP 模式。该选择不启动 Docker；ERP 服务仍按上面的命令启动，未配置 MCP 或 ERP 不可用时明确失败。

ERP Gateway 现在不再仅信任调用方：在签名之前，从既有治理 Store 检查 APPROVED、期限、Run、execution claim、exact approved request，复用原 Preparer 重新核对当前 Case version 和 Finalize。确定性的审批拒绝返回 RFQ_REQUEST_REJECTED，不读取旧回执把拒绝转成成功；其他外部不确定结果仍按原机制对账。没有重写 HITL/Runtime。
