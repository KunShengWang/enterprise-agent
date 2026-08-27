# OrderCare 单案例误路由 Incident 故障复盘

## 1. 问题背景

时间：2026-08-11

场景：运营人员希望处理一个明确、唯一的 OrderCare 异常订单案例，并完成事实查询、SOP 解释、恢复预演、人工审批、受控执行和最终一致性验证。

测试输入：

```text
请处理一个唯一的 OrderCare 单案例。

案例标识：requestId=ORDERCARE-M05-REQUEST。

请先查询该案例的订单、库存扣减和死信事实，并检索 OrderCare SOP。
如果 FlowOrder 返回 diagnosisCode=REPLAY_CANDIDATE、
recoveryEligible=true 且 hardRisks 为空，则创建恢复预演，
申请人工审批；审批通过后执行恢复，并验证扣减记录、库存和死信是否最终收敛。
```

预期执行目标：

```text
ORDERCARE_CASE
→ OrderCareExecutionAdapter
→ ordercare-floworder-v1
→ DefaultAgentRuntime（单 Agent）
```

预期业务流程：

```text
floworder_case_inspect
→ knowledge_search
→ floworder_recovery_preview
→ floworder_recovery_execute
→ WAITING_APPROVAL
→ 人工审批
→ 恢复原 Run
→ actionRequestId 对账
→ 最终收敛验证
```

## 2. 实际错误现象

前端没有选择 `OrderCare Agent`，而是显示：

```text
系统已根据任务类型选择 Incident Commander
→ 采用标准事故调查流程
→ 需要补充信息
→ 请说明需要调查的业务范围和相关业务标识
```

实际错误链路：

```text
用户明确输入唯一单案例和 requestId
→ LLM Router 错误选择 INCIDENT_INVESTIGATION
→ DefaultIncidentScopeRoutePreflight 被触发
→ Preflight 没有得到事故调查所需的范围锚点和异常组合
→ WorkItem 进入 WAITING_INPUT
→ 前端要求补充事故范围
```

这不是数据库数据缺失，也不是 OrderCare Agent 在执行过程中动态切换成多 Agent。

真正的问题发生在执行前：统一 Workbench 把 WorkItem 分发到了错误的 `ExecutionTarget`。

## 3. 必须区分的两个分类阶段

统一入口中存在两个容易混淆的分类过程。

### 3.1 WorkCommandClassifier

它判断用户输入与当前聚焦任务的关系，例如：

```text
NORMAL_GOAL
RESUME_ACTIVE_WORK
ADD_INPUT_TO_ACTIVE_WORK
START_NEW_WORK
PAUSE_ACTIVE_WORK
CANCEL_ACTIVE_WORK
ABANDON_ACTIVE_WORK
AMBIGUOUS
```

它不负责选择单 Agent 或多 Agent，也不负责选择 `ORDERCARE_CASE`。

本次成功日志中的第一步：

```json
{
  "commandType": "NORMAL_GOAL"
}
```

只表示用户提交了一个正常的新业务目标。

### 3.2 ExecutionTarget 路由

它负责从以下四个执行目标中确定一个：

```text
GENERAL_AGENT
ORDERCARE_CASE
INCIDENT_INVESTIGATION
INCIDENT_RECOVERY_PLAN
```

错误发生在这个阶段。错误修复也主要针对这个阶段。

## 4. 修改前的路由实现

修改前，`RoutingCoordinator` 的核心顺序是：

```text
RouteContextResolver
→ ExecutionTargetRegistry.enabledTargets
→ LlmUnifiedTaskRouter
→ IncidentScopeRoutePreflight
→ RoutePolicyValidator
→ RoutingStore.completeRouting
→ DispatchCoordinator
```

即：

```text
先把所有已启用目标交给 LLM
→ LLM 选择一个 targetId 并提取输入
→ Java 再校验这个结果能否执行
```

`ExecutionTargetRegistry` 中的业务定义本来是清楚的：

- `ORDERCARE_CASE`：由一个 `requestId`、`orderNo` 或 `deductNo` 标识的单个有界 FlowOrder 案例。
- `INCIDENT_INVESTIGATION`：基于多个明确标识或可发现业务范围执行只读多 Agent 事故调查。
- `INCIDENT_RECOVERY_PLAN`：基于一个可信、已评估 Incident 生成受控恢复计划。

但是这些定义最终仍由 LLM 解释，Java 没有在模型调用前建立确定性的单案/事故边界。

## 5. 根因分析

### 5.1 LLM 拥有过大的目标选择权

`LlmUnifiedTaskRouter` 同时看到四个 ExecutionTarget。

用户输入同时包含诊断、死信、恢复、审批和验证等复杂词汇，模型容易把“步骤多、风险高”错误理解为“事故调查”，从而选择 `INCIDENT_INVESTIGATION`。

但步骤多不等于多 Agent。是否使用 Incident Multi-Agent，首先应该由业务范围决定：

- 一个明确案例：OrderCare 单 Agent；
- 多个案例、批次、时间范围或明确事故调查：Incident Multi-Agent。

### 5.2 Prompt 只有单向保护

旧路由 Prompt 明确写了：

```text
不能仅因为输入中存在一个 requestId，
就把事故、批量任务、多 Agent 调查或批量恢复请求降级为单案例 OrderCare。
```

这条规则能够防止：

```text
Incident → 错误降级为 OrderCare
```

但没有反向规则阻止：

```text
明确 OrderCare 单案 → 错误升级为 Incident
```

### 5.3 RoutePolicyValidator 也只有单向边界

旧 Java 校验能识别批量、批次、事故调查、多 Agent 等范围语义，并阻止它们被自动分发到 `ORDERCARE_CASE`。

但模型选择 `INCIDENT_INVESTIGATION` 时，Validator 没有检查原始目标是否其实只是一个明确有界单案。

因此错误的 Incident 选择没有被反向拦截。

### 5.4 Incident Preflight 在错误目标上继续工作

当模型错误选择 Incident 后，`DefaultIncidentScopeRoutePreflight` 会按照事故流程寻找：

- `requestIds`；
- 时间范围；
- orderNo、deductNo、deadLetterId 等发现锚点；
- 超时、取消、死信、状态不一致等事故异常类型。

用户原始输入虽然有一个 `requestId`，但目标是单案例恢复，并不是范围发现任务。Preflight 因此返回 `REQUIRE_CLARIFICATION`。

### 5.5 前端澄清信息优先相信模型 missingInputs

`PublicPresentationService.clarificationPrompts` 原先主要读取：

```text
routing.decision.missingInputs
```

当模型没有返回具体 `missingInputs` 时，前端只能显示泛化提示：

```text
请说明需要调查的业务范围和相关业务标识。
```

用户明明已经提供 requestId，却仍被要求补充标识，进一步放大了错误感知。

### 5.6 Eval 缺少本次完整真实表达

旧 Eval 已有短输入：

```text
请诊断 requestId=ORDERCARE-M05-REQUEST，
如可安全恢复则创建预演并申请审批
```

但没有覆盖用户本次包含事实查询、SOP、Preview、HITL、Execute、Convergence 的完整表达。

模型在短句上路由正确，不代表在复杂真实输入上仍然稳定。

### 5.7 RAG SOP 存在文档漂移

`data/rag-docs/ordercare-recovery-sop-v1.md` 仍然写着：

```text
M1 只有 floworder_case_inspect 和 knowledge_search 两项只读能力，
没有 preview、execute。
```

但当前代码已经注册四项能力：

```text
floworder_case_inspect
knowledge_search
floworder_recovery_preview
floworder_recovery_execute
```

即使路由修复，Agent 检索到旧 SOP 后仍可能得到互相冲突的能力说明。

## 6. 外部调研结论

本次没有只靠项目经验判断，而是核对了官方资料。

### 6.1 Anthropic：Building effective agents

资料：<https://www.anthropic.com/engineering/building-effective-agents>

主要结论：

- 优先采用满足需求的最简单架构；
- 定义明确的任务适合可预测 Workflow；
- Routing 可以由 LLM，也可以由传统分类模型或确定性算法完成；
- Orchestrator-Workers 适合无法提前确定子任务的复杂问题。

对应本项目：明确唯一案例不需要升级成 Incident Multi-Agent。

### 6.2 LangChain/LangGraph：Router

资料：<https://docs.langchain.com/oss/python/langchain/multi-agent/router>

主要结论：

- Router 是执行前的独立预处理步骤；
- 可以使用 LLM，也可以使用 rule-based logic；
- 输入类别明确时适合确定性或轻量分类；
- Supervisor 更适合需要根据持续变化上下文动态编排的任务。

对应本项目：单案/事故属于可枚举的业务边界，应在执行前完成选择，而不是在 Agent Runtime 中动态切换。

### 6.3 LangChain/LangGraph：Custom workflow

资料：<https://docs.langchain.com/oss/python/langchain/multi-agent/custom-workflow>

主要结论：可以把确定性逻辑和 Agent 行为组合在一条执行图中。

对应本项目：

```text
Java 确定业务范围和候选目标
→ Agent 在选定目标内自主选择工具
```

### 6.4 Microsoft AutoGen：SelectorGroupChat

资料：<https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/selector-group-chat.html>

主要结论：

- 模型选择 Agent 不是唯一实现；
- 可以通过 `candidate_func` 缩小候选 Agent；
- 可以通过 `selector_func` 覆盖模型选择；
- 当 Prompt 中需要堆积大量 Agent 选择条件时，应考虑自定义选择函数或拆成确定性阶段。

对应本项目：不应继续把所有边界条件都堆进 Router Prompt。

## 7. 最终修复设计

最终采用：

```text
确定性业务范围识别
→ 候选 ExecutionTarget 过滤
→ 明确场景直接决定
→ 剩余歧义才调用 LLM
→ 候选越界检查
→ Java Policy Validation
→ Dispatch
```

修复后的完整路由主线：

```text
WorkCommandClassifier
→ AgentWorkItem
→ RoutingCoordinator
→ RouteContextResolver
→ ExecutionTargetRegistry.enabledTargets
→ ExecutionTargetCandidateResolver
   ├─ 明确单案例：生成确定性 ORDERCARE_CASE 决策
   ├─ 明确事故：只保留 INCIDENT_INVESTIGATION 候选
   ├─ 明确事故恢复计划：只保留 INCIDENT_RECOVERY_PLAN 候选
   ├─ 单案/事故冲突：REQUIRE_CLARIFICATION
   └─ 仍然模糊：交给 LLM Router
→ Candidate Set Enforcement
→ IncidentScopeRoutePreflight / RoutePolicyValidator
→ RoutingStore.completeRouting
→ DispatchCoordinator
```

## 8. 具体代码修复

### 8.1 新增 ExecutionTargetCandidateResolver

文件：

```text
src/main/java/com/agent/platform/workbench/application/ExecutionTargetCandidateResolver.java
```

职责：在调用路由模型之前提取可信的结构化业务信号，并缩小候选目标。

识别的显式单案标识：

```text
requestId
orderNo
deductNo
```

主要规则：

| 输入边界 | 结果 |
|---|---|
| 恰好一个显式业务标识，且没有事故范围 | 直接确定 `ORDERCARE_CASE` |
| 明确单案例但缺少业务标识 | 只保留 `ORDERCARE_CASE` 候选，由后续要求补充标识 |
| 多个 requestId、批量、批次、多个订单、明确事故或多 Agent 调查 | 只保留 `INCIDENT_INVESTIGATION` |
| 明确生成/制定/规划事故恢复计划 | 只保留 `INCIDENT_RECOVERY_PLAN` |
| 同时声明唯一单案和批量/事故调查 | 直接要求澄清 |
| 无法确定 | 保留已授权目标，由 LLM Router 判断 |

对本次输入，Resolver 得到：

```text
identifiers = [{type=requestId, value=ORDERCARE-M05-REQUEST}]
singleCaseIntent = true
incidentScope = false
boundedSingleCase = true
```

然后生成确定性结果：

```text
targetId = ORDERCARE_CASE
modelName = execution-target-candidates-v1
extractedInputs = {requestId=ORDERCARE-M05-REQUEST}
promptTokens = 0
completionTokens = 0
```

这不是把模型的错误结果偷偷改写成 OrderCare，而是明确业务边界根本不再调用目标选择模型。

### 8.2 RoutingCoordinator 接入候选解析

文件：

```text
src/main/java/com/agent/platform/workbench/application/RoutingCoordinator.java
```

核心逻辑变为：

```java
ExecutionTargetCandidateResolver.Resolution candidates =
        candidateResolver.resolve(claimedWork.originalGoal(), targets);

RouterModelResult modelResult = candidates.deterministicResult()
        .orElseGet(() -> router.route(new RoutingModelRequest(
                claimedWork,
                claimedWork.normalizedGoal(),
                candidates.candidates(),
                context.conversationSummary())));
```

含义：

```text
能确定 → 使用确定性结果
不能确定 → LLM 只能从服务器提供的候选集中选择
```

### 8.3 模型候选越界时失败关闭

即使模型收到一个受限候选集，仍不能假设它一定遵守。

新增校验：

```text
模型返回的 targetId 不在服务器候选集
→ RouteDisposition.REJECT
→ failureCode=TARGET_OUTSIDE_CANDIDATE_SET
→ 不进入 Preflight
→ 不 Dispatch
```

系统不会静默把错误目标改成另一个目标，避免隐藏错误和审计失真。

### 8.4 RoutePolicyValidator 增加双向边界

文件：

```text
src/main/java/com/agent/platform/workbench/application/RoutePolicyValidator.java
```

现在有两侧保护：

```text
事故/批量范围选择 ORDERCARE_CASE
→ 阻止事故降级

明确唯一单案选择 INCIDENT_INVESTIGATION
→ 阻止单案升级
```

CandidateResolver 是主要路径，Validator 是纵深防御。即使某个调用方绕过 CandidateResolver，也不能直接执行错误目标。

### 8.5 Eval Runner 与 Eval Suite 同步真实生产路由

修改文件：

```text
src/main/java/com/agent/platform/workbench/eval/WorkbenchRoutingEvalRunner.java
src/main/java/com/agent/platform/workbench/eval/WorkbenchRoutingEvalSuite.java
```

Eval Runner 现在也经过 CandidateResolver，避免测试路径与真实生产路径不一致。

新增完整回归案例：

```text
route-ordercare-complete-recovery
```

覆盖：

```text
唯一单案例
+ requestId
+ 订单/库存/死信事实
+ SOP
+ 安全恢复条件
+ Preview
+ HITL
+ Execute
+ Convergence
```

### 8.6 前端澄清信息改用服务器校验原因

文件：

```text
src/main/java/com/agent/platform/workbench/presentation/PublicPresentationService.java
```

新顺序：

```text
优先使用明确的 decision.missingInputs
→ 没有时映射 validation.reasons
→ 最后才显示安全的通用提示
```

对于单案/事故范围冲突，前端现在显示：

```text
请明确选择：唯一 OrderCare 单案例，或批量/多案例事故调查
```

不再统一要求“补充业务范围和标识”。

### 8.7 更新 OrderCare RAG SOP

文件：

```text
data/rag-docs/ordercare-recovery-sop-v1.md
```

删除旧的“M1 只有两项只读能力”限制，更新为当前四项注册能力和完整受控恢复流程：

```text
案例事实查询
→ SOP 检索与诊断
→ 恢复预演
→ 人工审批
→ 有界执行
→ actionRequestId 对账
→ 最终收敛验证
```

同时明确：

```text
SUBMITTED != RESOLVED
```

只有 `convergence.status=RESOLVED` 才能宣布业务恢复。

## 9. 为什么修复后日志中没有路由 LLM

修复后的 JSON 日志顺序是：

```text
Step 1：WorkCommandClassifier → NORMAL_GOAL
Step 2：输入安全分类 → ALLOW
Step 3：直接进入 OrderCare Agent，调用 Inspect + Knowledge Search
```

没有出现四目标 LLM Router，是因为：

```text
唯一显式 requestId + 无事故范围
→ CandidateResolver 已经确定 ORDERCARE_CASE
→ 不需要调用目标选择模型
```

这是修复生效的直接表现，不是日志缺失。

## 10. 修复后的完整 Run 证据

日志文件：

```text
help_remeber/json/OrderCare单Agent_Run_明确单案异常.json
```

### 10.1 单 Agent 边界

日志加载的是 OrderCare 专用 System Prompt，并且工具目录只有：

```text
knowledge_search
floworder_case_inspect
floworder_recovery_preview
floworder_recovery_execute
```

没有 Incident Commander、Specialist、Reviewer 或 `delegate_*` SubAgent Tool。

因此这是 OrderCare 单 Agent Run，不是 Incident Multi-Agent。

### 10.2 四项工具全部实际执行

实际 ToolCall 顺序：

```text
floworder_case_inspect
knowledge_search
floworder_recovery_preview
floworder_recovery_execute
```

不是只有工具注册信息，而是四项工具都产生了 ToolCall 和对应 ToolResult。

### 10.3 初始业务事实

Inspect 返回：

```text
diagnosisCode = REPLAY_CANDIDATE
factsComplete = true
recoveryEligible = true
hardRisks = []

order.status = TIMEOUT
deduct.status = ORDER_CREATED
inventory.lockedStock = 3
inventory.invariantOk = true
deadLetter.status = PENDING
```

说明：订单已经超时，但 `ORDER_TIMEOUT` 消息消费失败并进入死信，导致扣减没有释放，库存仍锁定 3 件。

### 10.4 SOP 检索

Knowledge Search 返回：

```text
success = true
enoughEvidence = true
source = ordercare-recovery-sop-v1.md
documentCount = 3
```

检索结果已经包含更新后的四项能力与完整 HITL 流程。

### 10.5 恢复预演

Preview 返回：

```text
proposalId = prop-895558aa-7474-3366-9f0b-41617364653f
proposalStatus = ACTIVE
canExecute = true
actionRequestId = act-820dccbc-7893-4589-a78e-0a6382a420c8
actionStatus = NOT_STARTED
caseOutcome = NOT_CONVERGED
```

### 10.6 HITL 与执行恢复

Agent 使用 Preview 返回的原始 `proposalId` 调用 `floworder_recovery_execute`。

恢复后的结果包含：

```text
proposalStatus = APPROVED
approvalId = 6c91996c-57ee-4da4-856e-bdcbb14b7349
approvedBy = workbench-reviewer
actionStatus = SUBMITTED
```

发起 Execute ToolCall 与恢复后最终模型调用之间约有 39 秒，恢复后的 ToolResult 继续使用同一个 Provider ToolCall ID：

```text
call_00_MTxLsJ5lBGZ3YJyODvj54987
```

说明原 Run 的 ToolCall/ToolResult 对被正确保留，审批后继续的是原 Run，而不是新建一个伪造执行。

### 10.7 最终业务收敛

确定性收敛检查返回：

```text
convergence.status = RESOLVED
attempts = 3
caseOutcome = RESOLVED
deductReleased = true
inventoryInvariantOk = true
relatedDeadLettersTerminal = true
```

因此本次不是只执行到：

```text
actionStatus=SUBMITTED
```

而是已经完成业务级最终一致性验证，可以判定业务真正恢复。

## 11. 自动化验证

新增测试：

```text
ExecutionTargetCandidateResolverTests
RoutingCoordinatorCandidatePolicyTests
WorkbenchRoutingSafetyGateTests 新增反向升级保护
PublicPresentationServiceTests 新增 validation reason 映射
WorkbenchRoutingEvalSuite 新增完整真实输入
```

核心测试覆盖：

- 完整 OrderCare 输入直接确定 `ORDERCARE_CASE`；
- 明确单案不调用 LLM Router；
- 确定性结果正确提取 requestId；
- 多个 requestId 只保留 Incident 候选；
- 单个 requestId 但明确事故调查时仍可进入 Incident；
- 事故恢复计划不会被折叠成 Incident Investigation；
- 单案与事故范围冲突时要求澄清；
- 模型不能把明确单案升级为 Incident；
- 模型不能把批量事故降级为 OrderCare；
- 前端能够使用 Java validation 原因生成具体澄清提示。

完整测试命令：

```powershell
mvn -q test
```

结果：

```text
tests = 443
failures = 0
errors = 0
skipped = 73
```

跳过项为依赖外部环境的测试，不是本次修复失败。

## 12. 手工复测注意事项

### 12.1 必须新建任务

旧 WorkItem 已经持久化了错误的 Incident 路由并进入 `WAITING_INPUT`。

修改代码后不要继续旧任务，应新建 WorkItem 或新会话重新测试，否则旧路由状态仍会被复用。

### 12.2 修改 SOP 后需要重新导入 RAG

后端启动后执行：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8083/api/agent/rag/ingest" |
  ConvertTo-Json -Depth 10
```

这会重新读取：

```text
data/rag-docs
```

并用更新后的 `ordercare-recovery-sop-v1.md` 替换 pgvector 中对应来源的旧分片。

### 12.3 成功标准

前端和日志至少应满足：

```text
显示 OrderCare Agent，而不是 Incident Commander
→ Inspect 成功
→ SOP 命中 ordercare-recovery-sop-v1.md
→ Preview ACTIVE/canExecute=true
→ 进入 HITL
→ 审批后恢复原 Run
→ actionStatus=SUBMITTED
→ convergence.status=RESOLVED
```

## 13. 本次修复带来的工程经验

### 13.1 Agent 数量由业务范围决定，不由步骤数量决定

一个案例即使包含诊断、知识检索、预演、审批、执行和验证，仍然可以是一个 Agent 的多步 Tool Calling Run。

只有需要跨案例、跨范围、跨领域并行调查时，才考虑 Incident Multi-Agent。

### 13.2 明确业务不变量必须写进 Java

以下边界不能只写在 Prompt：

```text
单案还是事故
候选目标权限
标识来源
批量范围上限
是否需要确认
高风险工具是否需要审批
最终是否真正收敛
```

Prompt 用于帮助模型理解，Java Policy 才是权威执行边界。

### 13.3 LLM 适合处理歧义，不适合拥有无约束路由权

推荐模式：

```text
确定性规则先解决明确情况
→ 候选过滤
→ LLM 解决剩余语义歧义
→ Java 校验模型结果
```

### 13.4 不静默改写模型结果

如果模型返回候选集外目标，应拒绝并记录：

```text
TARGET_OUTSIDE_CANDIDATE_SET
```

静默改写虽然可能让当前请求继续，但会隐藏模型和路由质量问题，也会破坏审计真实性。

### 13.5 测试必须使用真实用户长表达

短句 Eval 通过不代表真实场景稳定。路由测试应同时覆盖：

- 最短表达；
- 完整业务表达；
- 同义词和语序变化；
- 单案/事故冲突；
- 多 ID、批次、时间范围；
- Prompt Injection 和跳过审批请求。

### 13.6 文档也是运行时依赖

RAG SOP 不是普通说明文档。它会进入 Agent 上下文并影响决策，因此代码能力升级后必须同步更新并重新 Ingest。

## 14. 面试说明版本

可以将本次问题概括为：

> 统一 Workbench 原先让 LLM 在四个执行目标间直接路由。一个包含诊断、SOP、预演、审批和收敛验证的唯一订单案例，因为步骤复杂，被模型误判成 Incident Multi-Agent，随后事故范围预检要求用户重复补充范围。我们参考 Anthropic、LangGraph 和 AutoGen 的官方路由模式，将明确业务边界前移到 Java：新增确定性 CandidateResolver，唯一显式业务标识且无事故范围时直接选择 OrderCare；批量、多个 requestId 或明确事故时只保留 Incident 候选；歧义场景才调用 LLM。模型返回候选集外目标时失败关闭，Policy Validator 再提供双向升级/降级保护。同时补齐完整真实输入 Eval、前端澄清映射和 RAG SOP。最终完整 Run 证明四项工具全部执行，经过 HITL 后 convergence.status=RESOLVED，443 个测试全部通过。
