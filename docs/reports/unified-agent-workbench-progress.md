# Unified Agent Workbench V1 — 实施进度

> 更新时间：2026-07-20 CST

| 里程碑 | 状态 | Checkpoint | 测试 | 说明 |
|---|---|---|---|---|
| M0 | PASSED | `d1e785f` | 文档一致性复审 | V0.2.3 / FINAL |
| M1-A | PASSED | `efa0bcd`；证据修正 `359f2a5` | 专项 22/22；全量 146，0 failure/error | PostgreSQL 17 真实集成通过 |
| M1-B | PASSED | `ae68824` | 单元 6/6；PostgreSQL 5/5；真实模型 3/3；全量 152，0 failure/error | Router / Command Classifier / Routing Recovery；默认 Feature Flag 关闭 |
| M1-C | PASSED | `08aacbd` | M1-C 单元 4/4；PostgreSQL 6/6；M1-A～C PostgreSQL 30/30；全量 156，0 failure/error | 四 Adapter / Incident Preview / Idempotent Dispatch / Reconciliation；默认 Feature Flag 关闭 |
| M1-D-S0 Tenant Isolation Security Gate | PASSED | `52de061` | 新增 PostgreSQL 3/3；Workbench PostgreSQL 33/33；真实模型 3/3；全量 156，0 failure/error，11 个既有环境跳过 | SQL 级 tenant/principal/conversation/work 约束；删除 conversationId 全局唯一约束 |
| M1-D | PASSED | `af964e8` | Web/应用单元 16/16；Workbench PostgreSQL 34/34；真实模型 3/3；全量 159，0 failure/error；前端 build 通过 | 统一自然语言入口、服务端身份、Focus/历史、Preview 确认、四目标链接与 M1 本地时间线 |
| M1-E | PASSED | `2c5701e` | Eval 38 条：真实模型 37/38；安全门禁 7/7；PostgreSQL 47/47；全量 166，0 failure/error；前端 build 通过 | 命令 100%、Target 95.8%、处置 100%；所有危险指标为 0 |
| M2-A | PASSED | `30c7b9f` | Projector 单元 2/2；PostgreSQL 52/52；全量 168，0 failure/error；前端 build 通过 | 三权威来源、幂等 cursor、WorkLink 约束、并发产品 sequence、失败隔离；默认关闭 |
| M2-B | PASSED | 本报告所在本地 checkpoint | SSE/Controller 9/9；SSE PostgreSQL 3/3；PostgreSQL 55/55；全量 174，0 failure/error；前端 build 通过 | 复合 cursor、断线 replay、gap、eventId 去重、PRIMARY RUN delta、child Run 隔离 |

当前停止原因：无。应用内浏览器自动化运行时不可用，已在 M2-B evidence 中记录，不影响冻结硬门禁。
下一步：提交 M2-B 独立本地 checkpoint；重新读取冻结蓝图与 M2-C 门禁后进入聊天内 Multi-Agent 执行树。禁止 push。
