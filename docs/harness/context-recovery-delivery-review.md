# Context Recovery 交付审查（2026-10-07）

本次交付基于 `60edafa29f2954ac305c421c8ad3473cd2d3e63e`，沿用 `feat/context-reliability` 的 ContextAssembler、ContextPolicy、ToolResultProjector 和 ContextBudgetRecoveryTests。历史模型评测不重跑、不覆盖。

## 审查结论与补严

保留唯一 Runtime/ContextAssembler 路径。常规投影后仍超预算时，每次 assemble 最多一次恢复；仅对已持久化、身份匹配的成功只读 JSON 结果生成预览和原文引用。权威 Case、当前请求、审批/治理、来源证据和原始执行记录保持。恢复后仍装不下则沿用 CONTEXT_OVERFLOW；不增加模型调用、预算或写操作重试。

审查发现原 patch 的恢复资格没有显式检查 `execution.state == SUCCEEDED`，也没有独立拒绝原始 ToolResult 的 `manualReview`。补充这两个防御条件，防止状态/成功字段不一致或 Timeline 缺少人工复核标记时恢复结果；新增测试同时覆盖这两个反例。

专项命令：

```powershell
mvn.cmd -B '-Dtest=ContextBudgetRecoveryTests,ContextEngineeringTests,DefaultAgentContextManagerTests,HarnessCoreTests,DefaultAgentRuntimeStateTests' '-Dsurefire.reportNameSuffix=delivery-20261007-focused' test
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

专项测试 **67/67 PASS，0 FAIL / 0 ERROR / 0 SKIP**。最终采购/Replay/Full Java/build 结果以 `evals/context-reliability/reports/delivery-20261007/delivery-validation.json` 为准；每阶段有重复测试，不能相加为独立测试数量。

2026-10-07 首次 Full Java 为 **1256 total / 1239 PASS / 9 SKIP / 0 FAIL / 8 ERROR**；错误全部在 `StdioMcpToolGatewayTests`，日志为 fake server 初始 discovery 的 1 秒窗口超时、随后读取缺失工具定义。采购 796 PASS / 11 SKIP、Replay 380/380 和 package 均通过。测试源码、MCP Gateway、FakeMcpServer 和 pom 与干净 baseline 字节完全相同。

为检查既有启动敏感性，仅对测试进程临时使用历史报告中的 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1`，基线和补丁各执行一次同一 MCP class，结束后恢复环境；未改 timeout/断言/生产设置。基线为 9 PASS / 10 ERROR；补丁为 14 PASS / 1 FAIL / 4 ERROR，FAIL 是 discovery 未读到工具后 tool list 数量为 0。基线失败证明这类问题也存在于无补丁版本；不将不同次数推断为改善或恶化。对照不是生产性能测量，且不足以证明机器资源的具体根因。完整数字和日志归档；不再重复全量测试、不删除初次失败。**本次全量测试门禁未通过，不能标记 Full Java PASS；提交和集成不代表完成生产验收。**

## 证据口径

- 2026-10-06 原 patch：确定性门禁通过，唯一 exposed ROB-04 regression PASS。该模型结果对应补严前的 patch。
- 2026-10-07 最终源码：追加上述资格检查，重新执行确定性门禁；**新模型 trial 为 0**。不将历史 ROB-04 或 Phase 4 Smoke/Dev 当作本次最终源码的模型验收。
- Original Holdout-v1 永久保留 **11/12**，ROB-04 原失败 CONTEXT_OVERFLOW；历史单点通过不拼成 12/12 unseen Holdout。Blind、最终正式重复模型评测未执行。
- PostgreSQL IT 没有配置隔离数据库环境，仍按既有门控跳过，不声称真实数据库专项通过。
- 历史文档与原 artifacts 按原始字节归档，包含最初的 MCP timeout 失败、后续 A/B PASS、完整 Java 和单点模型回归，不改写历史。

## 交付范围

采用当前 `feat/harness-reliability` 作为集成目标，使用普通 merge 保留补丁提交；不移动旧标签、不改 `main`，不集成 Multi-Agent / Dynamic Tool Loading / Workflow 实验。源码、测试、报告和哈希证据使用明确路径清单提交；Python 缓存、临时日志和独立 source.patch 不提交。原工作区 Handler 用户注释保持未提交且原字节不变。

证据目录使用 Git `-text` 属性保留原始字节，避免跨平台换行转换破坏哈希。归档完整性、受保护文件/旧标签/旧 Holdout 哈希以及集成源码对应关系由交付检查确认。
