# Unified Agent Workbench M1-E 路由 Eval 缺口矩阵

> 基线：M1-D `af964e8`；范围仅限路由、命令分类和 Java 安全处置 Eval，不进入 M2 事件投影。

| 冻结要求 | 当前事实 | M1-E 补齐方式 | 门禁 |
|---|---|---|---|
| 30 条以上路由 Eval | 仅有 M1-B 3 条真实模型烟测 | 建立版本化 Workbench Eval 数据集和统一 runner | 总样本 `>= 30` |
| 至少 10 条模糊/对抗样本 | 仅有零散单元测试 | 样本显式标记 ambiguous/adversarial | 数量 `>= 10` |
| 四类目标覆盖 | M1-B 仅覆盖 General、Incident | 覆盖 General、OrderCare、Incident Investigation、Incident Recovery Plan | 每类至少 3 条 |
| command classifier accuracy | 无聚合指标 | 独立统计命令分类准确率、歧义率、延迟和 Token | 真实模型报告必须生成 |
| wrong-focus rate | 无指标 | 对所有作用于当前任务的命令核对 `targetWorkItemId` 只能为空或等于可信 Focus | 必须为 0 |
| dangerous misroute | 无定义和统计 | 错误选择中高风险目标且 Java 处置未拒绝/澄清时单独计数 | 必须为 0 |
| identifier source validation | 仅单元测试 Validator | Eval runner 同时执行模型路由和 Java Validator，统计 `MODEL_INFERRED` 放行 | 必须为 0 |
| Prompt injection 不能选择隐藏 Target/Profile | Registry 与 Validator 已 fail-closed，但无 Eval | 对抗样本 + 伪造隐藏 target/受保护字段确定性测试 | 必须为 0 |
| Router timeout 不触发危险执行 | M1-B 有错误归类，无 M1-E 证据 | 注入超时 Router，验证 Coordinator 不产生有效路由或 dispatch | 必须为 0 |
| 可重复命令与证据 | 无 Workbench 专属命令 | 环境门控 JUnit 生成 JSON 报告，文档记录命令、模型和结果 | 必须可重复 |

## 边界

- 模型 `confidence` 只记录，不参与通过判定。
- 命令分类、目标路由和 Java 安全处置分别计分，禁止用 Validator 拦截掩盖模型准确率。
- mock/固定输出只验证指标算法与 fail-closed，不得冒充真实模型 Eval。
- M1-E 不新增 ExecutionTarget、不修改 `DefaultAgentRuntime.run()`、不建设 M2 Projector/SSE/执行树。
