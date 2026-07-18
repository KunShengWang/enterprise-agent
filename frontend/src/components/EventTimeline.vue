<script setup lang="ts">
import { computed } from 'vue'
import type { AgentStreamEvent } from '../types/agent'
import JsonViewer from './JsonViewer.vue'

const props = defineProps<{ events: AgentStreamEvent[]; active?: boolean }>()

const visibleEvents = computed(() => props.events.filter((event) => event.type !== 'heartbeat'))

const eventInfo: Record<string, { label: string; group: string; explanation: string }> = {
  run_started: { label: 'Run 已创建', group: 'RUN', explanation: '创建持久化 Run，并固定 Session、Profile 与预算。' },
  run_pause_requested: { label: 'Run 请求暂停', group: 'PAUSE', explanation: '收到客户端中断，Runtime 将在下一个持久化安全边界暂停。' },
  run_paused: { label: 'Run 已暂停', group: 'PAUSE', explanation: 'Checkpoint 已安全落盘，可以使用同一个 Run ID 恢复执行。' },
  run_resumed: { label: 'Run 已恢复', group: 'RUN', explanation: '从审批点或故障检查点恢复原有执行状态。' },
  context_prepared: { label: '上下文就绪', group: 'CTX', explanation: '加载消息、Memory、Skill 与工具定义，并计算上下文预算。' },
  context_compacted: { label: '上下文已压缩', group: 'CTX', explanation: '上下文接近窗口上限，Runtime 对投影内容进行了压缩。' },
  model_started: { label: '调用模型', group: 'LLM', explanation: '将上下文与可用工具交给模型，等待下一步决策。' },
  model_delta: { label: '模型增量', group: 'LLM', explanation: '模型回答 Token 的增量投影。' },
  model_completed: { label: '模型决策完成', group: 'LLM', explanation: '模型返回最终文本或一个/多个工具调用。' },
  model_failed: { label: '模型调用失败', group: 'LLM', explanation: '模型超时、上下文溢出或供应商调用失败。' },
  tool_requested: { label: '模型请求工具', group: 'TOOL', explanation: '模型生成结构化 ToolCall，Runtime 为它创建全局执行 ID。' },
  policy_decided: { label: '工具策略判定', group: 'POLICY', explanation: '运行时强制执行 allow / ask / deny 权限策略。' },
  approval_required: { label: '等待人工审批', group: 'HITL', explanation: '高风险工具暂停执行，Run 与预算进入可恢复状态。' },
  tool_started: { label: '工具开始执行', group: 'TOOL', explanation: '工具调用通过策略检查，写入幂等执行记录。' },
  tool_completed: { label: '工具执行完成', group: 'TOOL', explanation: 'ToolResult 写回消息时间线，下一轮模型可继续规划。' },
  sub_agent_started: { label: 'Sub-Agent 启动', group: 'SUB', explanation: '创建隔离上下文、预算和能力白名单的子 Agent。' },
  sub_agent_completed: { label: 'Sub-Agent 完成', group: 'SUB', explanation: '子 Agent 仅将收敛结果返回主 Agent。' },
  run_completed: { label: 'Run 正常完成', group: 'DONE', explanation: '模型不再请求工具，Runtime 保存最终回答并结束。' },
  run_failed: { label: 'Run 执行失败', group: 'FAIL', explanation: 'Runtime 将异常收敛为终态并保存失败原因。' },
  run_cancelled: { label: 'Run 已取消', group: 'STOP', explanation: '收到取消或审批拒绝，Runtime 安全终止。' },
  stream_gap: { label: '事件流存在缺口', group: 'SSE', explanation: '客户端消费过慢，需要根据持久化序号补拉事件。' },
  transport_error: { label: 'SSE 传输异常', group: 'SSE', explanation: '传输层异常，未必代表持久化 Run 已失败。' },
}

function info(type: string) {
  return eventInfo[type] ?? { label: type, group: 'EVENT', explanation: 'Runtime 发布的结构化事件。' }
}

function time(value: string) {
  if (!value) return '--:--:--'
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3,
  }).format(new Date(value))
}
</script>

<template>
  <div class="event-timeline">
    <div v-if="!visibleEvents.length" class="empty-timeline">
      <span class="waiting-orbit" />
      <strong>{{ active ? '等待第一个 Runtime 事件…' : '尚未开始执行' }}</strong>
      <p>提交任务后，这里会按照数据库 sequence 展示完整事件时间线。</p>
    </div>

    <article v-for="(event, index) in visibleEvents" :key="event.eventId" class="event-item" :class="`event-${event.type}`">
      <div class="event-rail">
        <span class="event-node">{{ String(event.sequence).padStart(2, '0') }}</span>
        <i v-if="index < visibleEvents.length - 1" />
      </div>
      <div class="event-body">
        <div class="event-heading">
          <span class="event-group">{{ info(event.type).group }}</span>
          <strong>{{ info(event.type).label }}</strong>
          <time>{{ time(event.createdAt) }}</time>
        </div>
        <p class="event-explanation">{{ info(event.type).explanation }}</p>
        <p v-if="event.content" class="event-content">{{ event.content }}</p>
        <JsonViewer v-if="Object.keys(event.metadata ?? {}).length" :value="event.metadata" label="事件 payload" />
      </div>
    </article>
  </div>
</template>
