# Enterprise Agent · 采购 Agent

当前唯一核心业务场景是企业采购寻源、供应商比较和受控 RFQ。Procurement Agent 是唯一对外业务 Agent；Generic 历史记录仅供查看。公共 Runtime 保留内部 Profile 扩展能力。当前源码与测试优先于历史设计报告。

## 已有能力与边界

| 能力 | 当前实现 |
| --- | --- |
| Procurement Case | 模型提出 Case Patch，Java 校验可信身份、字段和版本，通过 CAS 持久化；动态采购事实不交给长期记忆 |
| Provider | 默认 synthetic 数据；可配置只读 MCP Provider。保留来源证据与 Java Eligibility 校验 |
| Specialist | 可选 Commercial / Delivery 子 Agent，复用通用 SubAgentRunner；只提供 advisory 分析 |
| Recommendation | 主 Agent 权衡候选；Finalize 重读 Case、校验版本、资格和证据 |
| RFQ HITL | 服务端重建不可变请求，人工批准后执行原请求；未知结果按幂等键查询，不能盲目重试 |
| Unified Workbench | 输入、任务路由、Dispatch、历史、Run、Trace、审批、预算及断线重放 |
| 公共平台 | Runtime、原生 Tool Calling、MCP、Memory、RAG、Multi-Agent、Trace、Eval、AgentOps |

RFQ 使用进程内 SimulatedProcurementRfqGateway，并非真实供应商 API、邮件或 ERP 对接。不创建真实采购订单、付款或合同，不能据此宣称跨进程 production exactly-once。

## 阅读与运行

- [构建和运行](docs/build-and-run.md)
- [当前架构](docs/architecture.md)
- [采购业务与安全边界](docs/procurement-sourcing.md)
- [采购评测](docs/procurement-evaluation.md)
- [Workbench 使用与契约](docs/unified-workbench.md)
- [API 指南](docs/api-guide.md)
- [文档索引](docs/documentation-index.md)

离线后端回归：`mvn.cmd -o clean test`（需要依赖已缓存）。前端在 frontend 中执行 `npm.cmd run build` 和 `npm.cmd test`，包含类型检查、生产构建与七组 smoke。真实模型 Live Eval 必须单独显式启用，不属于离线验收。

## 历史兼容与未验收范围

FlowOrder / OrderCare / Incident Command / Recovery Plan 已退役，旧领域源码和专属前端页面已删除。旧目标值仅用于历史解析和 TARGET_RETIRED 拒绝，不能确认、派发、续跑或执行旧工具。已物化 WorkItem、WorkEvent、Run、Trace、审批历史仍保留只读展示；原持久化状态不自动改写。

旧表和已有 pgvector 向量未清理。没有隔离 PostgreSQL 环境时，IT 应标记 BLOCKED / NOT RUN；离线回归不能代替真实数据库历史兼容验收。详见 [退役及验收边界](docs/retirement-and-history.md)。
