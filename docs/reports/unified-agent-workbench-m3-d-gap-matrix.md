# Unified Agent Workbench M3-D 缺口矩阵

更新时间：2026-07-20 CST

| 蓝图要求 | M3-D 前代码事实 | 本阶段实现与证据 | 结论 |
|---|---|---|---|
| 完整路由与命令 Eval | M1-E 只有 38 条路由样本 | 扩展为 20 条命令、60 条路由，其中 31 条模糊/对抗输入 | CLOSED |
| 危险参数与来源门禁 | 已校验 `MODEL_INFERRED`，未覆盖同值冒充多类标识 | 新增 30 条参数来源和 30 条安全策略样本；拒绝一个值同时充当多个危险标识类型 | CLOSED |
| 真实模型证据 | M1-E 只有 38 条模型 Eval | DeepSeek 80 条：79/80；危险误路由、危险命令、错误 Focus、来源违规、隐藏 Target 均为 0 | CLOSED |
| 命令、故障与恢复 | M3-C 有专项测试，缺统一复现入口 | `workbench-m3-d-evidence.ps1` 提供 31 条故障恢复门禁和稳定数据库参数传播 | CLOSED |
| 业务 Eval | OrderCare/Incident 分散，脚本不可统一执行 | OrderCare 20 条真实模型 Eval 通过 19 条；Incident Runtime E2E 4/4 | CLOSED |
| ToolCall 协议健壮性 | Provider 在 JSON 前输出说明时会被误判成最终回答 | Gateway 识别带前置说明的 ToolCall 信封，并强化首字符协议；单元测试覆盖 | CLOSED |
| 危险审批绕过 | “忽略审批/跳过审批”未被确定性信号覆盖 | 输入阶段进入结构化安全决策，模型与工具前 fail closed | CLOSED |
| Eval 误报控制 | “是否已恢复”会被子串规则误判为已验证副作用 | 仅豁免疑问/未确认上下文；肯定式“业务已恢复”仍判违规 | CLOSED |
| 可重复证据与面试演示 | 缺统一命令和讲解顺序 | 新增证据脚本与面试演示手册 | CLOSED |
| 最终回归 | 尚未在 M3-D 当前代码上执行 | 后端 271 条、PostgreSQL 72/72、前端生产构建全部通过 | CLOSED |

## 设计边界

- 不修改 `DefaultAgentRuntime.run()`。
- 不创建新 ExecutionTarget，不改变 Incident Command 或 Recovery Plan 状态机。
- Incident E2E 的 FlowOrder、RabbitMQ Management 和模型使用确定性隔离 Stub；不能表述为真实外部中间件 E2E。
- OrderCare 与 Workbench 路由 Eval 使用真实模型；数据库故障门禁使用真实 PostgreSQL。
- M3-D 不宣称跨数据库、模型供应商、RabbitMQ 和 FlowOrder 的 exactly-once。

不存在待选择的架构分支，M3-D 可以进入最终证据与 checkpoint 门禁。
