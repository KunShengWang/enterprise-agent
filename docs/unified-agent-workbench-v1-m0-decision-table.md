# Unified Agent Workbench V1：M0 冻结决策表

> 对应蓝图：[unified-agent-workbench-v1-design.md](./unified-agent-workbench-v1-design.md)
> 蓝图版本：V0.2.1 / FINAL FOR M0 FREEZE
> 更新时间：2026-07-19 CST
> 当前结论：PASSED，允许冻结 M0，允许后续开始 M1-A

## 1. 冻结规则

本表冻结编码前必须稳定的架构语义，不冻结需要通过 Eval 校准的运行参数。“通过”表示不存在待选择的架构分支；表中的实现验收约束仍必须在对应里程碑通过自动化测试证明。

## 2. 决策总表

| # | 决策主题 | V0.2.1 冻结结论 | 状态 | 实现验收约束 |
|---:|---|---|---|---|
| 1 | WorkItem、Input 与 Relation | `agent_work_input` 先落库；除 START_NEW_WORK 外控制命令不创建 WorkItem；Recovery Plan 创建独立子 WorkItem | 通过 | RESUME、ABANDON、PAUSE、CANCEL、ADD_INPUT 不新建 WorkItem；Recovery Plan 使用 `RECOVERY_OF` |
| 2 | Router 审计身份 | Router 使用 `agent_routing_decision`，不是 Agent Run | 通过 | 独立统计模型、Prompt digest、Token、延迟、结果和失败，不计入业务 Agent Run 数 |
| 3 | Target 与 Command 边界 | Registry 只有四个 ExecutionTarget；WorkCommand 独立分类和处理 | 通过 | WorkCommand 不进入 Target Catalog；只有 NORMAL_GOAL 进入 Router |
| 4 | WorkEvent 顺序语义 | WorkEvent 是派生产品时间线，sequence 是投影提交顺序 | 通过 | 保留 sourceSequence、双时间、correlationId、causationId；单事务锁定 WorkItem 分配 sequence |
| 5 | MODEL_DELTA | 在线透传与 Run timeline 回放双通道 | 通过 | 不逐 token 复制到 WorkEvent；断线可恢复且不混入子 Agent 正文 |
| 6 | 兼容入口 | M1 保留 `/api/agent/runs/**` 和 `/api/incidents/**` | 通过 | 新入口调用应用服务而非本机 HTTP 反调 Controller；旧入口用于回归和高级调试 |
| 7 | Incident 启动门禁 | 所有 Incident Investigation 固定 Preview → Explicit Confirmation → Start | 通过 | 未确认时 Commander/Specialist/Reviewer Run 数为 0；确认绑定 Preview 版本与范围 |
| 8 | 预算冻结范围 | 只冻结层级、配置项、累计不重置和 fail-closed | 通过 | 具体阈值由 Eval/成本数据校准；预算未知时危险路径 fail-closed |
| 9 | Conversation Focus | 使用 `agent_conversation_work_state` 保存单一 focused WorkItem | 通过 | `focused != running`；Focus 使用 CAS、验证所有权，不影响后台执行或权限 |
| 10 | START_NEW_WORK | 创建新 WorkItem 并切换 Focus，默认不修改旧 WorkItem | 通过 | 只有显式 Pause/Cancel/Abandon 改变旧任务；Abandon → Start 分别审计和分配 causationId |
| 11 | Principal/Tenant | 产品控制面统一 `tenant_id + owner_principal_id` | 通过 | 所有权链确定性校验；Recovery 子任务不继承父权限；Relation 禁止跨 tenant |
| 12 | WorkCommandClassifier 审计 | 使用 `agent_work_command_decision` 独立审计 deterministic/model 分类 | 通过 | 按钮 model calls=0；模型分类记录 Token/延迟/digest；同 inputId 只有一个 EFFECTIVE 决策 |
| 13 | ValidatedExecutionInput | extractedInputs 只作为候选，Java 形成带来源的强类型输入 | 通过 | MODEL_INFERRED 的危险标识禁止 Dispatch；Incident 范围和 Recovery incidentId 来源受限 |

## 3. Conversation Focus 冻结语义

```text
focused WorkItem != running WorkItem
```

- 一个 Conversation 最多一个 Focus，但可以有多个后台 RUNNING WorkItem；
- 无显式 ID 的 Resume、Add Input、Pause、Cancel、Abandon 默认指向 Focus；
- Focus 变更使用 version CAS，并验证 tenant、owner Principal、Conversation；
- Focus 只负责代词解析和前端当前视图，不赋权、不停止后台任务；
- 无 Focus、Focus 不可操作或存在歧义时 fail-closed 并澄清。

## 4. START_NEW_WORK 冻结语义

```text
START_NEW_WORK
→ 创建新的 WorkItem
→ CAS 将 Conversation Focus 切换到新 WorkItem
→ 默认不暂停、不取消、不放弃旧 WorkItem
```

明确复合命令“放弃旧任务并开始新任务”拆成两个受控动作：

```text
ABANDON_ACTIVE_WORK
→ START_NEW_WORK
```

两者分别记录 command decision、WorkEvent、CAS 结果和 causationId。前一个失败时后一个默认不执行。

## 5. Principal/Tenant 冻结语义

确定性校验链为：

```text
AuthenticatedPrincipal
→ Conversation 所有权
→ Focus/目标 WorkItem 所有权
→ 父 WorkItem/Incident 可访问性
→ Target 权限
→ ValidatedExecutionInput
→ Dispatch
```

请求体、metadata、模型输出不能覆盖 tenant、Principal、roles。WorkRelation 不允许跨 tenant；WorkLink 只关联当前 WorkItem 的 dispatchRequestId 创建或查询得到的执行对象。

## 6. Command Classifier 审计冻结语义

`agent_work_command_decision.classifier_type` 固定为：

```text
DETERMINISTIC_BUTTON
DETERMINISTIC_PROTOCOL
MODEL
```

Eval 必须能够统计：

- resume/new/abandon/add-input accuracy；
- ambiguous rate；
- wrong-focus rate；
- classification latency；
- classification Token cost；
- dangerous command misclassification count。

分类结果不能绕过 WorkCommandHandler；分类失败不得默认执行任何控制命令。

## 7. 输入与模型消息边界

```text
agent_work_input = 统一产品入口收到的用户输入事实
agent_message = 某个具体 Agent Run 的模型上下文消息
```

只有进入 General/OrderCare Run 的业务目标或经校验的补充输入可以投影到相应 `agent_message`。Resume、Pause、Cancel、Abandon、Focus 切换、Incident Preview Confirmation 等产品控制命令不得无差别写入模型上下文。

## 8. ValidatedExecutionInput 来源

标识来源固定为：

```text
EXPLICIT_USER_INPUT
TRUSTED_CONVERSATION_CONTEXT
SERVER_RESOLVED_FROM_BATCH
MODEL_INFERRED
```

`MODEL_INFERRED` 的 requestId、orderNo、deductNo、queueName、incidentId 不能 Dispatch；Incident 范围来自显式输入或服务端解析；Recovery Plan incidentId 来自已验证父 WorkItem/Conversation Context。

## 9. M1 编码顺序

M0 冻结后，后续严格按以下顺序编码：

```text
M1-A AgentWorkItem / Input / Relation / Conversation Focus
→ M1-B Router / WorkCommandClassifier
→ M1-C Idempotent Adapter / Dispatch Reconciliation
→ M1-D 最小统一页面
→ M1-E 路由 Eval
```

不得跳过 M1-C 先做前端演示，也不得把 dispatch reconciliation 延后到 M3。

## 10. 最终复审结论

```text
复审结论：PASSED
复审范围：Blueprint V0.2.1 与本决策表的一致性
复审时间：2026-07-19 CST
剩余阻塞项：无
允许冻结 M0：是
允许开始 M1-A：是
备注：允许开始不代表本轮已经开始；本轮在文档冻结后停止。
```
