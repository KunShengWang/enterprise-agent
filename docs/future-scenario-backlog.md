# 后续业务场景待决策清单

> 更新时间：2026-07-19
> 本文只记录可能的后续方向，不代表已经进入实施计划、当前里程碑或简历完成范围。

## 1. OrderCare 事故指挥 Agent Team

- 候选场景 ID：`ordercare-incident-command-v1`
- 当前状态：`PHASE_1_IMPLEMENTED / PHASE_2_IMPLEMENTED / PHASE_2_E2E_PASSED`
- 当前设计：[OrderCare Incident Command V1：事故调查与受控恢复 Multi-Agent 设计](ordercare-incident-command-v1-design.md)
- 决策：V1.4 已完成 Phase 1 只读事故调查和 Phase 2 受控 Recovery Planner。Phase 2 只生成有证据引用、范围受限的 ProposalRequest，并逐项复用 FlowOrder Proposal/HITL/幂等执行/收敛；不增加批量写接口。真实 PostgreSQL CAS 与包含 Phase 1 调查、Planner、审批、执行和收敛的 Runtime E2E 已通过。Phase 3 多实例生产化仍未启动。

目标场景：当大量订单超时、库存释放死信堆积或消息消费异常时，由 Incident Commander 动态委派订单、MQ、库存和 SOP Specialist 并行调查，通过持久化结构化消息汇总证据，由 Reviewer 检查冲突并提出受控处置建议。

若启动，最低边界包括：

1. 主 Agent 动态委派，而不是固定角色 Prompt 拼接；
2. 子 Agent 具备独立 Run、上下文、Profile、工具白名单和预算；
3. 使用持久化 Task/Event Store，通过 `agent_task_event` 传递受控结构化证据和澄清请求；Phase 1 不建设通用 Agent Mailbox；
4. 支持有限的 Reviewer 追问、超时、取消和父子 Run Trace；
5. 限制最大深度和并行数，禁止 Agent 无限互聊；
6. Agent 负责调查和建议，批量恢复、灰度、幂等与收敛仍由确定性程序和 FlowOrder 执行。

该场景不应为了展示 Multi-Agent 而拆分当前单订单恢复流程。只有跨事实域、可并行调查且存在证据冲突的事故级任务才使用 Agent Team。

## 2. FlowOrder 预约购买助手

- 候选场景 ID：`floworder-purchase-assistant-v1`
- 当前状态：`DEFERRED / PENDING_DECISION`
- 决策：当前只保留设计方向，后续再决定是否实现。

目标场景：普通用户通过自然语言完成预约资格查询、参数补齐、购买预演、确认提交和异步进度查询；异常时生成受控 Handoff，转交 `ordercare-floworder-v1`，不继承运营恢复权限。

建议业务链路：

```text
购买意图
-> 资格与资源查询
-> 不可变购买预演
-> 用户确认
-> 使用稳定 requestId 提交 FlowOrder V8
-> 确定性查询预约和订单状态
-> 成功返回订单 / 异常转交 OrderCare
```

若启动，最低边界包括：

1. 新建独立 Purchase Profile，禁止把购买工具加入 OrderCare Profile；
2. `userId` 来自可信认证上下文，不能由模型或请求体任意指定；
3. FlowOrder 负责资格、额度、库存和请求状态的权威判断；
4. Preview 时持久化并绑定稳定 `requestId`、用户、资源、库存项和数量；
5. Execute 必须绑定用户确认，网络结果未知时查询原 `requestId`，禁止换 ID 盲目重试；
6. 接口受理成功不等于购买成功，最终结果由预约请求和订单状态判定；
7. 当前 FlowOrder 没有完整商品、价格、购物车、支付和售后，因此只能表述为预约/抢购助手，不能宣称完整电商购物 Agent；
8. 未完成可信身份与授权前，只允许固定测试用户的本地演示，不宣称生产安全。

## 3. 候选场景关系

```text
floworder-purchase-assistant-v1
  负责正常预约购买和进度解释
                |
                | 异常 Handoff
                v
ordercare-floworder-v1
  负责单订单诊断与受控恢复
                |
                | 批量事故升级
                v
ordercare-incident-command-v1
  负责多事实域并行调查与事故处置建议
```

三个场景可以共享 Agent Runtime、FlowOrder 契约、Trace、Guardrail 和 Eval 基础设施，但必须隔离身份、Profile、工具权限、会话与风险策略。

## 4. 启动决策门禁

启动任何候选场景前，应先回答：

1. 它是否解决现有用户或运营人员的真实问题；
2. 为什么需要 Agent，而不是普通表单或固定 Workflow；
3. 是否存在可复现的真实业务数据链路和 E2E 证据；
4. 是否会削弱当前 OrderCare 主闭环的稳定性和面试叙事；
5. 是否具备身份、权限、幂等、UNKNOWN 处理、审计和 Eval 的最低设计。

未通过上述门禁时保持 `DEFERRED`，不开始编码。
