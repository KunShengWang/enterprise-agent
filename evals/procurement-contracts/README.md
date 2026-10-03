# Phase 2.5 — Business-Stabilized Single-Agent Run

本目录仅保存两项 Production Contract Fix 的独立实验及报告。不是 Harness，不新增 Agent Runtime/Loop，也不优化 Prompt、工具选择、加载、Retry 或 Context。

冻结的 Eval 在 `../procurement-v2`，tag `procurement-eval-v2.1`，commit `13759383e988cafee936f5ac297299080eba9cc3`。B0 永久保持 114/120、38/40 三次全过；不覆盖其中任何文件。V2.0 至 V2.1 的成绩变化是评分规则修正，不能称为 Agent 提升。

`run.py` 只负责独立批次与输出目录，直接调用冻结 V2.1 的 `run_trial`、`manifest`、`summarize` 和 `link_attempts`，复用其真实模型 Driver、ERP reset/reseed、grader 与 metric definitions。固定原来的 40 tasks × 3；infra attempts 保留，不能择优重跑有效失败。完整运行前后检查冻结 Eval 全目录字节哈希。

```powershell
$contractTemp=(Resolve-Path 'target/procurement-v2/jvm-temp').Path
$env:TEMP=$contractTemp
$env:TMP=$contractTemp
python -B evals/procurement-contracts/run.py --run-id business-stabilized-20261003-r1
# 仅用于恢复已查明并解除的 infra 阻塞，生产和实验源码必须完全一致：
python -B evals/procurement-contracts/run.py --run-id business-stabilized-20261003-r1 --resume
python -B evals/procurement-contracts/verify.py
```

运行使用同一专用演示 ERP，会按原冻结逻辑 reset/reseed；禁止并发运行其他 ERP reset/demo/benchmark。模型配置与实际 resolved identity、Prompt、模型可见 Tool schema、seed、tasks、grader 逐项记录。`verify.py` 离线检查原始 artifacts、120 个 trial、隔离和控制变量，不修改评分。

Phase 2.5 依用户要求暂不 commit；实验记录的是 Eval commit 上的 dirty production working tree，并提供实际生产文件哈希。不得将 Eval commit SHA 误称为已经包含修复的生产提交。

新输出统一为 `reports/business-stabilized-*`。`tests.json` 记录定向测试、真实 ERP IT、Legacy 回归与 smoke；`eval-freeze-push.json` 记录 Part A 远端核验；开发日志与 smoke 原始证据保存在 `development-evidence.zip`。最终报告将区分业务契约修复收益与模型波动，不称为 Harness improvement。

完整运行现已结束：120 valid trials，118 PASS / 2 FAIL，38/40 三次全过；另有 3 infra attempts。原 ANOM-05、RFQ-02 共六次失败全部通过；新增 E2E-04-1、ROB-03-1 的冻结 FAIL 均记录了 ERP `idx` 漂移证据，原分数保留，因此本轮不满足未来 Harness B0 候选条件。122 份完整 artifact 离线重评分一致，原 B0/Eval 全目录字节未变。最终分析见 `../../docs/baselines/procurement-v2-contracts-stabilization.md`。

`business-stabilized-infra-evidence.zip` 保留三次 infra 的完整或部分原始材料；`production-review-snapshot.zip` 保存执行时 16 个授权源码/测试文件，排除用户 Handler 文件。历史 `interruption-402.json` 和 checkpoint 仅是中断时记录；当前结果以 `business-stabilized-summary.json` 为准。

只有两类六次失败消失、没有新回归且安全不退化时，结果才可提交人工审查作为未来 Harness B0 候选。人工 Review 前不创建 `procurement-harness-b0`，不提交或推送 Phase 2.5。
