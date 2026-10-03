# Procurement Eval V2.2 — Canonical Business State

本版本修复 Eval infrastructure 的业务状态规范化，不修改 Production、Agent、Prompt 或工具契约。V2.1 tag、代码、旧 114/120 B0 与 Phase 2.5 的 V2.1 118/120 全部原样保留。

`canonical_state.py` 通过逐实体字段白名单构造业务投影；`grade_v22.py` 只替换传入原 V2.1 grader 的业务状态哈希。原事实/版本校验、审批、副作用、任务期望、Dev/Holdout 和效率指标定义均保持不变。

顶层 Supplier/Item 的 `idx` 是本次误报来源。不能递归删除所有 `idx`：申请单、RFQ Item、RFQ Supplier、UOM 与 Budget Distribution 保留列表顺序和 `ordinal=idx`。外层实体集合按业务 ID 排序；Item-Supplier 按 `(itemId, supplierId, relationId)` 排序；不合并重复业务实体，重复主键或缺少业务字段直接报错。

三种哈希：完整业务状态包含 RFQ；master hash 覆盖企业主数据；protected hash 覆盖主数据和运行前已有 RFQ。原“不准修改主数据”的 invariant 使用 protected hash，批准后新增 RFQ 仍须通过原审批/数量/载荷/关联 grader，不是忽略 RFQ。`reports/world-contract.json` 保存默认 seed 的规范业务投影供后续 preflight 使用；未重写或运行 V2.1 的 live runner。

```powershell
python -B -m unittest discover -s evals/procurement-v2.2 -p 'test_*.py' -v
python -B evals/procurement-v2.2/regrade.py
python -B evals/procurement-v2.2/verify.py
```

`regrade.py` 仅读取原 Phase 2.5 archive，以 archive SHA、每个 artifact SHA、任务/trial、生产 source manifest、Prompt、tool schema、model identity 绑定 120 个有效 trial。还核对全部 122 份完整 artifact 的原 V2.1 评分。3 次 infra 尝试及 replacement accounting 原样保留，不因 V2.2 改记为有效结果。本轮新增模型调用为 0。

原生产测试对象是 `13759383e988cafee936f5ac297299080eba9cc3` 加未提交 Phase 2.5 修复，不能把该 Git SHA 当作已提交的修复版本。`harness-b0.json` 以实际 production working-tree hash、源码快照和协议/证据文件哈希定义 Harness B0，并保留全部效率指标。它是人工 Review 前的文件冻结记录，没有 commit/push/tag。

完整审计见 [审计报告](../../docs/baselines/procurement-v2.2-canonical-hash-audit.md)。`verify.py` 对冻结结果做严格核验；它对 120/120 的断言是验证已记录结果，不参与 grader 或开发目标。如果重评发现不同结果，应保留新证据并报告，不能改规则满足断言。
