# 退役、知识 source 与待验收边界

FlowOrder、OrderCare、Incident Command、Recovery Plan 已退出当前业务。保留旧 target/scenario/tool 的识别与 TARGET_RETIRED 拒绝，不恢复执行、不映射采购。公共历史 WorkItem、WorkEvent、Run、Trace 与审批可只读查看；RUNNING/WAITING_APPROVAL 等原始状态不能自动改写。

## RAG 来源

默认根目录为 `data/rag-docs`，LocalDocumentLoader 递归扫描，以相对根目录的路径（斜杠标准化）作为 `source`。旧 SOP 的默认 source 是 **`ordercare-recovery-sop-v1.md`**，版本 `ordercare-sop-v1`，旧领域契约 `floworder-recovery-case-v1`；现已移至 `docs/archive/floworder/knowledge/`。

`incident-response.md` 是通用应急响应知识，仍用于 DefaultRagEvalRunner 的 `rag-incident-response`（P1、10 分钟响应、复盘报告），保留不变。它不是已删除 Incident Agent 的可执行指南，也不授权高风险动作。

PgVectorKnowledgeIngestionService 调用 replaceBySources，仅替换本次扫描发现的 source。移走旧文件后再次导入不会清除旧 source。既有 `rag_chunk` 仍可能包含旧 SOP，本轮未连接数据库、未查询或删除向量。

## 未来精确退役向量的前置条件

1. 独立授权并确认目标库、备份和隔离测试库；先只读核对 `rag_chunk.source`、chunk 数量、content_hash、内容版本及导入根配置。若过去根目录不同，source 可能含路径前缀；不得用关键词或模糊匹配批量删除。
2. 使用经确认的 source 等值白名单，只处理旧 SOP，不碰 `incident-response.md`、采购资料或 Memory。repository 的 deleteBySource 使用 `source = ?`，这是实现位置说明，不是本轮执行命令。
3. 在隔离副本验证事务/回滚、重复执行幂等、删除数、其他 source 不变，以及 RAG 检索/Eval 不退化。变更后应处理检索缓存失效，防止旧结果继续出现。
4. 审阅具体计划与结果后再决定真实库操作；本轮未提供执行授权，也未清理已有数据。

## PostgreSQL 与最终提交门禁

需在明确隔离环境验证：物化历史读取/回放、旧 source 在 LIMIT 与 lease 前排除、旧 Preview/Dispatch/审批不能执行、CAS/去重/fencing/租户隔离及当前采购投影。无隔离环境标记 BLOCKED / NOT RUN。应用的开发默认数据库及残余 IT Fixture 默认 URL 都不能作为验收环境。

旧表原样保留；历史 SQL 仅作文本证据。最终提交前还需审查全轮次 diff、grader v2 独立范围、冻结 benchmark 和前端保护哈希、未跟踪归档及索引。当前没有暂存、提交或推送。离线测试通过不等于真实数据库兼容或 FlowOrder 数据清理最终完成。
