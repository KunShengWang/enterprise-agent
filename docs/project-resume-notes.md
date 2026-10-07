# enterprise-agent 简历与面试事实清单

更新：2026-10-07。交付分支为 `feat/harness-reliability`，采用 Single Procurement Agent + Harness Core + Context Engineering + MCP/ERPNext。此文件供项目描述使用；实测结果以相应报告和冻结源码为准。

## 可直接使用的方法描述

**企业采购 Agent 平台｜Java、Spring Boot、Spring AI、PostgreSQL、MCP、ERPNext**

面向自然语言采购需求，构建需求澄清、供应商比较、证据推荐及人工审批后创建 ERP RFQ 的业务闭环，结合模型的理解与权衡能力和 Java 的确定性校验，确保推荐与写操作基于可信业务事实。

- 基于 Spring AI 原生 Tool Calling 构建统一 Agent Harness，由单一运行时管理模型与工具循环、执行预算、工具超时、取消及审批暂停恢复；通过工具效果分类实现只读瞬态错误的有界重试，记录逻辑调用和物理执行过程。
- 设计权威状态驱动的上下文工程，每轮重新读取采购 Case 与版本，对工具结果做结构投影和去重；超预算时执行一次有界恢复，保留需求、治理信息、证据身份和原文引用，避免大结果挤占上下文并污染业务状态。
- 通过 MCP 和领域 Adapter 对接 ERPNext，将 RFQ 意图转为服务端重建的确定请求，结合审批绑定、CAS 版本校验、幂等键与不确定结果查询，控制重复写入及过期审批；模型不直接授权或执行 ERP 写操作。

上述三点可按简历篇幅压缩，不应替换成“实现完整 Agent SDK”“生产 exactly-once”或“全流程无人采购”。

## 结果的允许口径

| 证据 | 可以怎么写 | 限制 |
|---|---|---|
| 2026-10-05 Reliability 版本 | 冻结 Smoke 9/9、Dev 28/28 业务通过，五项冻结安全指标零违规 | 开发集观测，且对应恢复补丁之前的源码；不是泛化成功率 100% |
| 2026-10-06 原恢复补丁 | 大工具结果溢出的 ROB-04 专项模型回归通过 | 已曝光用例、单次回归；Original Holdout 仍为 11/12 |
| 2026-10-07 补严后的交付源码 | 专项确定性测试 67/67；其余最终本地检查见交付报告 | 本轮未运行模型，不能将前两行当作最终源码模型验收 |

证据：[Reliability 模型验收](harness/harness-reliability-acceptance-report.md)、[恢复补丁交付审查](harness/context-recovery-delivery-review.md)、[历史 MCP 诊断与模型回归](harness/context-recovery-mcp-diagnostic.md)。

## 面试时需要说明的边界

- ERPNext 接入和创建 RFQ 已实现；评测业务事实来自合成数据，referencePrice 是参考价，不是供应商正式报价。Legacy synthetic backend 的 RFQ 仍使用进程内模拟网关。
- PostgreSQL 持久化与相关校验已经实现；隔离 PostgreSQL 专项 IT 未验收，不能写成真实数据库故障恢复全部通过。
- 已实现审批和幂等治理；未证明真实供应商端跨进程 production exactly-once。
- Supervisor Multi-Agent、Dynamic Tool Loading、确定性 Stage Workflow 有独立实验分支，未进入本次交付路线。
- 不包含订单、收货、发票、付款、报价回收后的完整再决策流程；生产认证、秘密管理、数据库迁移和运维仍待验收。

提交和测试完成情况见[本轮交付报告](delivery-20261007.md)。本轮专项 67/67、采购回归与 Replay、构建通过；Full Java 因既有 MCP fake server 发现超时出现 8 ERROR，基线对照也失败，不能描述为全量测试通过。结果数字不是简历方法描述的必需部分；只在能解释评测集合、源码版本和分母时使用。
