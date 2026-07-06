# V1.2 真实模型接入

## 这一版做了什么

V1.2 把项目默认运行模式改成真实 LLM：

```text
PromptRequest
  -> SpringAiLlmService
  -> Spring AI Prompt
  -> DeepSeek ChatModel
  -> 模型回答
```

Agent 主链路没有交给框架黑盒，仍然由 `V1AgentExecutor` 显式编排：

```text
Memory
Guardrail
SkillSelector
IntentRouter
QueryRewrite
RAG / Tool
PromptAssembler
LLM
OutputGuardrail
Trace / Eval
```

## 为什么还保留 MockLlmService

`MockLlmService` 只保留为测试和离线调试入口，不再是默认运行路径。

默认配置：

```yaml
enterprise-agent:
  mock-mode: false
```

只有显式打开时才会启用：

```yaml
enterprise-agent:
  mock-mode: true
```

## 你现在应该重点看

```text
pom.xml
src/main/resources/application.yaml
src/main/java/com/agent/platform/llm/LlmService.java
src/main/java/com/agent/platform/llm/SpringAiLlmService.java
src/main/java/com/agent/platform/prompt/DefaultPromptAssembler.java
src/main/java/com/agent/platform/agent/V1AgentExecutor.java
```

学习重点不是 DeepSeek 这个供应商，而是这层抽象：

```text
AgentExecutor 不直接依赖 DeepSeek。
AgentExecutor 只依赖 LlmService。
SpringAiLlmService 负责把项目内部 PromptRequest 转成 Spring AI Prompt。
以后换 OpenAI / Ollama / 其他模型时，主链路不应该被推翻。
```
