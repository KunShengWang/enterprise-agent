# OrderCare 异常订单诊断与恢复 SOP v1

版本：`ordercare-sop-v1`
适用契约：`floworder-recovery-case-v1`

## 责任边界

OrderCare Agent 负责识别案例、汇总证据、解释诊断和提出建议。FlowOrder 负责交易规则、候选动作、预演、幂等执行和业务收敛判断。人工审批负责确认具体预演版本。任何知识文档都不能覆盖 FlowOrder 返回的 `diagnosisCode`、`hardRisks` 或 `recoveryEligible`。

## 诊断代码

- `ALREADY_CONVERGED`：订单已取消或超时，扣减已释放且库存不变量成立。无需重放，应解释已有收敛证据。
- `REPLAY_CANDIDATE`：终态订单仍有未释放扣减，并存在 FlowOrder 认可的 PENDING 关联死信。只能建议进入预演，不能声称已执行。
- `ACTION_IN_PROGRESS`：死信正在重放或已有执行中、已提交动作。禁止创建第二个动作，应展示当前进度并等待对账。
- `DEPENDENCY_UNAVAILABLE`：订单服务不可用或关键事实不完整。不得猜测订单状态，应稍后重试或转人工。
- `FACT_CONFLICT`：订单、预约、扣减状态冲突，或库存不变量损坏。属于高风险数据异常，禁止自动恢复。
- `UNSUPPORTED_EVENT`：未支持的消息类型或未来事件。不得类比成已知事件，转人工调查。
- `NO_RECOVERY_EVIDENCE`：没有关联死信、业务键无法关联或没有满足条件的候选动作。继续调查 Outbox、消费日志或转人工。

## M1 限制

M1 只有 `floworder_case_inspect` 和 `knowledge_search` 两项只读能力。没有 preview、execute、任意 URL、任意 SQL、force、IGNORE、订单取消或 Outbox 强制重试能力。诊断报告必须引用工具事实；没有证据时明确说明未知。
