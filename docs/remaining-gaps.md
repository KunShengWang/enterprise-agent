# 仍然存在的边界

这份清单用于约束简历和面试表述，不是继续无止境堆功能的任务列表。

## 高优先级但不属于本轮 Runtime 重构

1. 身份认证与管理 API 授权
   - 当前没有 Spring Security、OAuth2 或租户管理后台。
   - Tool Runtime 能识别 metadata 中的角色/tenant，但 HTTP metadata 仍需可信网关签发，不能直接信任公网请求体。

2. 真正执行隔离
   - 当前提供文件根目录、网络 Host 白名单和 MCP 进程边界。
   - 这不是 OS/容器 Sandbox；Shell、文件和网络高风险能力若扩展，需容器、seccomp、低权限用户和出站网络隔离。

3. 数据库迁移治理
   - 代码使用 `CREATE TABLE IF NOT EXISTS` 便于学习启动。
   - 正式部署应使用 Flyway/Liquibase、版本化 DDL、回滚方案和旧 `record_json` 数据迁移。

## 已知技术边界

- `JsonAgentModelGateway` 使用提示词约束的 JSON ToolCall 协议；还没有针对不同模型 Provider 的原生 Tool Calling Adapter。
- SSE 转发持久化 Runtime 事件，不解析模型 JSON 的逐 Token 增量；`MODEL_DELTA` 尚未成为主路径事件。
- Token/Usage 优先采用 Provider 返回值，无法取得时使用估算并标记 source；成本按运维配置价格估算，默认关闭，仍不是财务账单。
- Sub-Agent 并行使用单进程有界线程池，没有消息队列、跨节点调度、优先级和长期任务接管。
- RAG 文档加载仅支持 UTF-8 文本格式，未接入 PDF/DOCX/PPTX OCR 和复杂表格解析。
- RAG 语义重排调用通用 ChatModel，不是专用 cross-encoder；成本和延迟需要基于真实数据评测。
- Prompt Injection 语义分类与主模型共用 LLM Service，尚未使用独立安全模型或外部内容安全服务。
- PostgreSQL RAG 缓存提供 TTL 和容量裁剪，但没有 Redis 的高吞吐与主动广播失效能力。
- 运行台已展示 OrderCare 案例、Proposal、审批、UNKNOWN 对账和崩溃恢复结果，但还没有生产级身份认证、租户隔离和运维告警大盘。

## 证据边界

- 已验证 `mvn clean test`、Spring Context 启动和现有测试通过。
- 最终 HTTP 冒烟以 Mock ChatModel 验证 Runtime/数据库/同步/SSE 连接，不代表真实 DeepSeek ToolCall 质量。
- 当前 64 条 enterprise-agent 默认测试（7 个外部 E2E 默认跳过）覆盖 Spring Context、预算/Profile 续接、审批并发、原子恢复、SSE、ToolResult 边界和 OrderCare M3；另有真实 PostgreSQL 响应丢失/崩溃恢复、FlowOrder 真实 RabbitMQ E2E 和 20/20 真实模型 Eval。仍缺真实多节点并发抢租约、长时间容量、网络分区和上下文溢出系统测试，因此不要声称“完善的测试体系”。

## 可以继续做，但只有拿到证据后再写简历

- Testcontainers + PostgreSQL/pgvector 集成测试；
- 多实例 Session Lease 竞争验证；
- 多节点 FlowOrder Action 租约竞争与网络分区验证；
- 长上下文压缩质量 Eval；
- 真实模型下 ToolCall 成功率、RAG Recall@K、重排增益、P95 延迟和成本；
- 容器 Sandbox 与可信身份边界。
