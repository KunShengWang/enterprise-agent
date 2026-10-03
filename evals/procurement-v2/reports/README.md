# B0 报告与历史证据索引

当前完整 frozen B0 是 `b0-20261003-v2.1-r1`：120 valid trials，114 PASS / 6 FAIL，38/40 tasks 三次全过。

| 文件 | 身份与用途 |
|---|---|
| `b0-summary.json`、`b0-trials.jsonl` | 当前 V2.1 汇总与全部 122 attempts（120 valid + 2 infra） |
| `b0-evidence.zip`、`b0-evidence-index.json` | 当前 V2.1 的 122 份完整 artifact；按索引校验 SHA256 |
| `b0-infra-evidence.zip`、`b0-infra-evidence-index.json` | 当前 V2.1 两次 402 的原始日志、快照及 replacement ledger |
| `b0-integrity-audit.json` | 当前 V2.1 全量离线重评、冻结与 trial 隔离核验 |
| `../baseline-v2.1.json`、`../fixtures/freeze-v2.1.json` | 当前完整 Manifest 与逐文件 freeze；生产 SHA 不包含未提交 Eval 实现 |
| `pre-freeze-r2/` | 原 V2.0 105/120 结果、原实现及原始证据，完整保留，不作为 V2.1 成绩 |
| `freeze-audit/missing-tool-audit.json`、`rfq-link-audit.json`、`inactive-item-audit.json` | 旧 V2.0 逐项失败审计，后两文件也位于 `freeze-audit/` |
| `freeze-audit/pre-freeze-b0-summary.json`、`pre-freeze-b0-trials.jsonl` | 旧批次补充 accounting、model identity、token 统计，后者也位于 `freeze-audit/` |
| `freeze-audit/v2.1-known-failures.json` | 新 V2.1 六次已知业务失败的诊断 |
| `freeze-audit/v2.1-archive-safety.json` | 当前新证据包完整性及配置密钥值检查 |
| `b0-v2.1-partial-evidence.zip`、`freeze-audit/v2.1-checkpoint-audit.json` | V2.1 余额中断时的历史 checkpoint（26 valid + 2 infra），已被完整结果取代，保留作追溯 |
| `b0-checkpoint-75*`、`b0-partial-evidence.zip` | 原 V2.0 的历史 checkpoint / partial 包，不是新 V2.1 |
| `abandoned-r1.json` | 早期 V2.0 r1 弃用声明，不拼接进任何正式 B0 |
| `order-independence.json`、`world-preflight.json` | 既有真实顺序 smoke 与 ERP world 预检 |
| `freeze-audit/order-independence-audit.json`、`order-evidence.zip` | 既有真实顺序 smoke 按 V2.1 离线检查，后者也位于 `freeze-audit/` |
| `legacy-regression.json` | 本次审计实际执行的 Legacy 全量回归及 Python 合约测试记录 |

旧 126 attempts = 120 valid + 6 infra = 122 complete + 4 partial；新 122 attempts = 120 valid + 2 infra = 122 complete + 0 partial。不能仅凭两个版本都拥有 122 份完整 artifact 混用证据。

正式比较引用完整 V2.1 Manifest；评分语义修正后的成功率不作为 Agent 性能优化证据。所有历史 checkpoint 均不允许替代完整 B0。
