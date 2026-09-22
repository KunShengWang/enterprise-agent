# 当前未完成事项

采购核心链路已具备 Case、只读 Provider、可选 Specialist、Recommendation 和模拟 RFQ HITL。剩余事项不能写成已交付：

- 明确隔离 PostgreSQL 环境中的历史 WorkItem/WorkEvent/Run/审批读取，租户隔离、CAS、lease/fencing 与退役拒绝验收。
- 真正供应商 RFQ 适配器的持久幂等及事实对账；当前模拟网关不证明跨进程 exactly-once。
- 生产认证、授权、秘密管理、迁移与运维流程。
- 已有旧 SOP 向量的 source 精确退役；本地文件归档不等于删除向量。
- 最终提交审计：分离已有 grader v2 与各轮改动、确认冻结 Benchmark 不变、检查索引和受保护文件；当前未暂存/提交。
- 真实模型 Eval 需另行授权并遵守冻结数据规则，不因文档收尾自动运行。

不在当前范围：采购订单、付款、完整 P2P、采购 Child Run 执行树与聚合预算。旧业务不再作为未来待恢复能力。详见 [边界](retirement-and-history.md)。
