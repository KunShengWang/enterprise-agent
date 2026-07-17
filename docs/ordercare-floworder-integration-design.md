# OrderCare × FlowOrder 早期集成设计记录

> 状态：`SUPERSEDED`
> 原设计日期：2026-07-16
> 替代版本：Blueprint V1.1

## 1. 为什么保留此文件

这份文件用于记录项目从“通用 Agent 能力集合”转向“异常订单诊断与受控恢复”业务主线的设计来源，不再作为编码规范。

当前唯一实施蓝图是：

- [Enterprise Agent 项目总蓝图：OrderCare Incident Agent](enterprise-agent-master-blueprint.md)
- [OrderCare 实施状态与学习地图](ordercare-implementation-status.md)

任何字段、工具数量、状态机或时序与总蓝图冲突时，必须以总蓝图为准。

## 2. 被保留的核心决策

早期设计中以下判断仍然有效：

1. `enterprise-agent` 与 FlowOrder 独立部署，通过受控 HTTP 契约集成。
2. 两个项目不共享数据库，Agent 不复制订单和库存状态机。
3. Agent 负责理解、诊断、解释和建议；确定性 Java 负责校验、执行和验证。
4. 高风险恢复必须经过人工审批。
5. 写请求超时不能盲目生成新幂等键重试。
6. 接口调用成功不等于业务恢复成功，最终必须回查业务收敛。
7. OrderCare 继续使用统一 Agent Run/SSE 窗口，不新增一组松散业务 Controller 模拟工作流。
8. FlowOrder 使用 typed HTTP，而不是将核心交易恢复包装为 MCP。

## 3. 已被 V1.1 替代的设计

以下早期方案已经废弃，禁止继续照此编码：

| 早期方案 | V1.1 决策 |
|---|---|
| 5 个模型可见 FlowOrder 工具 | 最终只允许 inspect、SOP、preview、execute 四个能力；动作查询和轮询属于内部协调器 |
| 模型反复调用 case 工具判断收敛 | Java `RecoveryConvergenceChecker` 有界轮询并返回结构化结果 |
| `proposalId == actionRequestId` | Proposal 与 Action Request 语义和标识分离，并保持一对一绑定 |
| enterprise-agent 保存业务 Proposal 状态机 | FlowOrder 是 Proposal 与 Recovery Action 的事实源；Agent 只保存引用和审计副本 |
| 审批只绑定 proposalId | 审批绑定版本、状态指纹、影响摘要、警告摘要和过期时间 |
| execute 直接接收 deadLetterId/actionRequestId | 模型侧 execute 只提交 proposalId，服务端恢复原始不可变参数 |
| M2 即“生产级” | M2 仅为 Resume Ready；UNKNOWN、租约、重启和重复 resume 完成后才是 Interview Strong |

## 4. 当前实施顺序

```text
M0.5 FlowOrder Recovery Baseline（已通过）
-> M1 案例聚合与只读诊断
-> M2 Proposal + HITL + 确定性收敛检查
-> M3 UNKNOWN + 租约 + 重启恢复 + 20 条 Eval
-> M4 服务认证与部署硬化
```

历史细节可以通过 Git 记录查看；保留一份已经冲突的长设计正文会误导编码和面试表述，因此不在当前分支重复维护。
