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

## 当前能力与受控恢复流程

OrderCare Agent 当前只允许使用以下四项注册能力：

1. `floworder_case_inspect`：根据一个明确的 `requestId`、`orderNo`、`deductNo` 或 `deadLetterId` 查询订单、扣减、库存和死信事实。
2. `knowledge_search`：检索适用的 OrderCare SOP。知识文档只能解释流程，不能替代 FlowOrder 实时事实。
3. `floworder_recovery_preview`：只有当 FlowOrder 返回 `diagnosisCode=REPLAY_CANDIDATE`、`recoveryEligible=true` 且 `hardRisks` 为空时，才能创建不可变恢复预演。
4. `floworder_recovery_execute`：只能使用当前 Run 中预演返回的 `proposalId` 申请执行。该工具属于高风险工具，Runtime 必须暂停并等待人工审批，审批通过后才能执行。

标准顺序为：案例事实查询 → SOP 检索与诊断 → 恢复预演 → 人工审批 → 有界执行 → `actionRequestId` 对账与最终收敛验证。

系统没有任意 URL、任意 SQL、`force`、`IGNORE`、伪造审批、订单取消或 Outbox 强制重试能力。`SUBMITTED` 只表示动作已可靠提交；只有确定性收敛检查返回 `convergence.status=RESOLVED`，才能说明扣减记录、库存和死信已经真正收敛。结果无法证明时必须返回 `UNKNOWN` 或 `MANUAL_REVIEW`，不能重新生成 ID 盲目重试。
