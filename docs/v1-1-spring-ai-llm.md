# V1.1 Spring AI LLM 适配层

## 这一版学什么

V1.1 的重点是把项目内部的 `LlmService` 抽象接到 Spring AI：

```text
PromptRequest
  -> SpringAiLlmService
  -> Spring AI Prompt
  -> ChatModel.call()
  -> ChatModel.stream()
```

也就是说，`V1AgentExecutor` 不直接关心 DeepSeek、OpenAI 或 Ollama，它只依赖：

```text
LlmService
```

这就是“框架负责能力，项目负责编排”的落点。

## 和 V1.2 的关系

V1.1 先加适配层。

V1.2 再把默认运行模式切成真实模型：

```yaml
enterprise-agent:
  mock-mode: false
```

因此现在默认会启用：

```text
SpringAiLlmService
```

`MockLlmService` 只保留给离线测试或单元测试，不再作为默认运行路径。

## 你应该重点看

```text
src/main/java/com/agent/platform/llm/LlmService.java
src/main/java/com/agent/platform/llm/SpringAiLlmService.java
src/main/java/com/agent/platform/prompt/DefaultPromptAssembler.java
src/main/java/com/agent/platform/agent/V1AgentExecutor.java
```

重点看清楚：

```text
主执行链路不依赖具体模型供应商。
真实模型只是 LlmService 的一种实现。
以后接 DeepSeek / OpenAI / Ollama 都不应该改 AgentExecutor。
```
