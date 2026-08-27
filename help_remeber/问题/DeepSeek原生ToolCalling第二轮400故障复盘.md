# DeepSeek 原生 Tool Calling 第二轮 400 故障复盘

## 1. 问题背景

时间：2026-08-07

场景：在统一控制台跟踪一次通用单 Agent 的 `knowledge_search` ToolCall/ToolResult 回环。

输入：

```text
请先查询企业内部知识库，再解释库存释放 SOP。回答必须基于检索结果并注明资料来源；
如果没有检索到相关资料，就明确说明没有检索到，不要使用通用知识补充。
```

预期流程：

```text
用户输入
→ General Agent 第一次调用模型
→ 模型返回 knowledge_search ToolCall
→ Runtime 执行 RAG 检索
→ ToolResult 写回 Timeline
→ 第二次调用模型
→ 模型基于检索结果形成最终回答
```

实际流程在最后一次模型调用处失败：

```text
第一次模型调用成功
→ knowledge_search 执行完成，但没有检索到相关资料
→ ToolResult 正常写回
→ 第二次 POST /chat/completions 返回 400 Bad Request
```

## 2. 关键异常

完整 HTTP 日志中的 Provider 响应：

```json
{
  "error": {
    "message": "The `reasoning_content` in the thinking mode must be passed back to the API.",
    "type": "invalid_request_error",
    "param": null,
    "code": "invalid_request_error"
  }
}
```

对应调用：

```text
callId=llm-http-32：第一次 Agent 模型调用，返回 ToolCall
callId=llm-http-33：ToolResult 后的第二次模型调用，返回 400
```

这说明故障不在命令分类、任务路由、输入 Guardrail 或知识库执行，而在 ToolResult 后的 Provider 会话协议回放。

## 3. 排查过程

### 3.1 先按调用阶段缩小范围

日志证明以下阶段均正常：

- 命令分类结果为 `NORMAL_GOAL`。
- 任务路由结果为 `GENERAL_AGENT`。
- 输入 Guardrail 结果为 `ALLOW`。
- 第一次 Agent 模型调用返回原生 `knowledge_search` ToolCall。
- Runtime 已执行知识库工具，并生成“没有相关证据”的 ToolResult。

因此排除“路由错误”“模型没有调用工具”和“RAG 本身抛异常”。

### 3.2 比较第一轮响应与第二轮请求

第一轮 DeepSeek 响应同时包含：

```json
{
  "reasoning_content": "...",
  "tool_calls": [
    {
      "id": "call_00_CGpZX6PgyivgamLKXZov3294",
      "function": {
        "name": "knowledge_search",
        "arguments": "{\"query\":\"库存释放SOP\",\"topK\":10}"
      }
    }
  ]
}
```

第二轮请求中的 assistant 历史消息只有 `tool_calls`，缺少 `reasoning_content`。这与 400 响应完全对应。

同时还发现 Provider 返回的 ToolCall ID 被替换成了 Runtime UUID。虽然本次 assistant/tool 两条消息使用了相同 UUID，不是当前 400 的直接原因，但这会破坏 Provider 原生消息的精确回放。

### 3.3 继续追到配置覆盖问题

应用配置原本是：

```yaml
spring.ai.deepseek.chat.model: deepseek-chat
spring.ai.deepseek.chat.temperature: 0.2
spring.ai.deepseek.chat.max-tokens: 4096
```

但是 Tool Calling 路径为了注册 ToolCallback，重新创建了一个只包含 ToolCallback 的 `DeepSeekChatOptions`。Spring AI 2.0.0 会为这个新对象补上自己的默认值，实际请求因此变成：

```json
{
  "model": "deepseek-v4-flash",
  "temperature": 0.7
}
```

`deepseek-v4-flash` 返回了思考模式字段 `reasoning_content`，从而暴露了 Runtime 没有保存和回放该字段的问题。

## 4. 根因

这次故障是两个问题叠加造成的。

### 根因一：Prompt 级 Tool Calling 配置覆盖应用配置

为了向模型注册 Tool Schema，代码直接 `DeepSeekChatOptions.builder()` 创建新配置，导致模型、温度和最大 Token 数没有继承 `application.yaml`，而是落到了 Spring AI 默认值。

### 根因二：Runtime 的持久化消息模型不完整

原来的 `AgentModelTurn` 只保存：

- assistantText
- toolCalls
- rawResponse
- usage
- finishReason

它没有表示 `reasoning_content` 的字段。流式累加器也只累计普通文本和 ToolCall，所以第一轮响应中的思考内容在进入 Runtime 后丢失。

第二轮重建消息时使用普通 `AssistantMessage`，无法把 DeepSeek 要求的 `reasoning_content` 传回 Provider，最终触发 400。

## 5. 修复方案

### 5.1 保留应用配置

通过 `DeepSeekChatProperties.toOptions()` 获取 Spring Boot 已绑定的真实配置，再调用 `mutate()` 添加 ToolCallback。

修复后的原则：

```text
应用配置
→ 复制为 Prompt 配置
→ 只追加本轮 ToolCallback
→ 不改变 model、temperature、maxTokens 等已有选项
```

相关代码：

- `AgentModelGatewayConfiguration`
- `NativeToolCallingAgentModelGateway.toPrompt`

### 5.2 捕获并持久化 reasoning_content

- 在非流式响应中，从 `DeepSeekAssistantMessage#getReasoningContent()` 读取。
- 在流式响应中，逐块累计 `reasoning_content`。
- 在 `AgentModelTurn` 中增加 `reasoningContent`。
- ToolCall 写入 Timeline 时，把它作为仅供 Provider 回放使用的内部元数据保存。
- 不把该字段发布成前端事件或最终回答。

### 5.3 分离 Provider ID 与 Runtime executionId

两种 ID 的职责不同：

| ID | 用途 |
|---|---|
| Provider ToolCall ID | 回放 assistant `tool_calls` 和 tool `tool_call_id`，满足模型协议 |
| Runtime UUID | 工具执行、审批、幂等、检查点和审计 |

Runtime 继续使用自己的 UUID 执行业务，但重建 Provider 消息时使用原始 Provider ToolCall ID。

### 5.4 使用 DeepSeekAssistantMessage 回放

第二轮请求不再使用普通 `AssistantMessage`，而是重建：

```text
DeepSeekAssistantMessage
├── content
├── reasoningContent
└── toolCalls（使用原始 Provider ToolCall ID）

ToolResponseMessage
└── tool_call_id（使用同一个原始 Provider ToolCall ID）
```

## 6. 修改文件

- `src/main/java/com/agent/platform/runtime/AgentModelGatewayConfiguration.java`
- `src/main/java/com/agent/platform/runtime/AgentModelTurn.java`
- `src/main/java/com/agent/platform/runtime/AgentProviderMetadata.java`
- `src/main/java/com/agent/platform/runtime/DefaultAgentRuntime.java`
- `src/main/java/com/agent/platform/runtime/NativeToolCallingAgentModelGateway.java`
- `src/test/java/com/agent/platform/runtime/AgentModelGatewayConfigurationTests.java`
- `src/test/java/com/agent/platform/runtime/DefaultAgentRuntimeStateTests.java`
- `src/test/java/com/agent/platform/runtime/NativeToolCallingAgentModelGatewayTests.java`

## 7. 回归验证

针对性测试：

```text
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
```

覆盖内容：

- Tool Calling 配置保留 `deepseek-chat / 0.2 / 4096`。
- 流式 reasoning_content 能正确累计到 `AgentModelTurn`。
- Runtime 能把 Provider ID 和 reasoning_content 写入内部 Timeline 元数据。
- 第二轮能够重建 `DeepSeekAssistantMessage`。
- assistant ToolCall 与 ToolResponse 使用相同的原始 Provider ToolCall ID。

完整测试：

```text
Tests run: 353, Failures: 0, Errors: 0, Skipped: 11
BUILD SUCCESS
```

## 8. 为什么这个问题比较难

### 8.1 表面看起来像知识库失败

工具结果是 `success=false`，很容易误判成 RAG 导致任务失败。但这里的 `success=false` 只表示“没有检索到相关证据”，它应该作为事实返回给模型，由模型形成“没有检索到”的最终回答。

真正的异常发生在下一次模型调用。

### 8.2 第一轮成功不代表协议实现完整

工具调用协议不是一次请求，而是一个回环：

```text
assistant tool_calls
→ tool result
→ assistant final answer
```

只验证模型能生成 ToolCall 不够，还必须验证第二轮历史消息能够被 Provider 接受。

### 8.3 OpenAI 兼容不等于字段完全一致

DeepSeek 使用 OpenAI 风格的 `tools/tool_calls`，但思考模式额外要求回放 `reasoning_content`。如果 Runtime 把 Provider 消息过早降维成通用文本和 ToolCall，就会丢失 Provider 专属的会话状态。

### 8.4 配置覆盖发生在框架适配层

业务配置写的是 `deepseek-chat`，普通 LLM 请求也确实使用该配置；只有 Tool Calling 分支创建了新的 Prompt options，才切换到 Spring AI 默认模型。这种分支级配置漂移仅查看 `application.yaml` 很难发现，必须比较完整 HTTP 请求。

## 9. 面试回答模板

### 9.1 一分钟版本

我在项目中遇到过一次原生 Tool Calling 的第二轮请求失败。第一次模型调用可以正常生成知识库 ToolCall，工具也执行完成，但 ToolResult 写回后，DeepSeek 返回 400，提示思考模式下必须回传 `reasoning_content`。

我先通过完整 HTTP 日志按阶段排查，确认路由、Guardrail、第一次模型调用和工具执行都正常，把问题定位到第二轮 Provider 消息重建。进一步发现 Tool Calling 分支重新创建了 `DeepSeekChatOptions`，覆盖了应用配置并切换到默认的 `deepseek-v4-flash`；同时 Runtime 的通用消息模型只保存文本和 ToolCall，没有保存 DeepSeek 的 `reasoning_content`。

最后我做了三部分修复：保留 Spring Boot 绑定的模型配置；在流式响应、运行时状态和 Timeline 中保存 Provider reasoning；把 Provider ToolCall ID 与内部 executionId 分离，并用 `DeepSeekAssistantMessage` 精确回放。然后增加协议层和 Runtime 回归测试，完整 353 个测试通过。

### 9.2 面试官继续追问“你学到了什么”

可以回答：

1. Tool Calling 必须测试完整回环，不能只测第一轮是否生成 ToolCall。
2. Runtime 内部 ID 和 Provider 协议 ID 不能混用，两者职责不同。
3. 统一消息模型需要允许保存 Provider 扩展状态，否则在推理模型、多模态模型或新协议上容易发生信息丢失。
4. Prompt 级 options 会覆盖模型默认配置，配置验证必须以下游实际 HTTP 请求为准。
5. 工具返回业务失败不等于 Runtime 异常；“没有证据”也应该作为 ToolResult 交给模型完成最终回答。

## 10. 后续手工验证

重新启动应用后，在统一控制台再次输入原始问题。应观察到：

```text
第一次模型请求
→ knowledge_search ToolCall
→ ToolResult 表示没有相关资料
→ 第二次模型请求返回 200
→ 最终回答明确说明没有检索到相关资料
```

第二轮完整 HTTP 请求应满足：

- assistant 消息包含第一轮返回的 `reasoning_content`。
- assistant `tool_calls[].id` 使用原始 Provider ToolCall ID。
- tool 消息的 `tool_call_id` 与其完全相同。
- Tool Calling 请求仍使用配置的 `deepseek-chat`、`temperature=0.2` 和 `max_tokens=4096`。
