import type { AgentStreamEvent } from '../types/agent'

export type RuntimeActivityTone = 'working' | 'waiting' | 'paused' | 'error' | 'done'

export interface RuntimeActivity {
  text: string
  tone: RuntimeActivityTone
  animated: boolean
}

const ignoredEventTypes = new Set(['heartbeat'])

function textMetadata(event: AgentStreamEvent, key: string) {
  const value = event.metadata?.[key]
  return value === null || value === undefined ? '' : String(value).trim()
}

function numberMetadata(event: AgentStreamEvent, key: string) {
  const value = Number(event.metadata?.[key])
  return Number.isFinite(value) ? value : 0
}

function toolName(event: AgentStreamEvent) {
  const name = textMetadata(event, 'toolName')
  return name ? `「${name}」` : '当前工具'
}

function activity(text: string,
                  tone: RuntimeActivityTone = 'working',
                  animated = true): RuntimeActivity {
  return { text, tone, animated }
}

function activityFromRunState(runState: string): RuntimeActivity | null {
  switch (runState.toUpperCase()) {
    case 'CREATED':
      return activity('任务已创建，正在等待 Runtime 接管…')
    case 'RUNNING':
      return activity('Runtime 正在继续执行当前任务…')
    case 'PAUSE_REQUESTED':
      return activity('已收到暂停请求，正在到达安全检查点并保存进度…', 'waiting')
    case 'PAUSED':
      return activity('任务已在安全检查点暂停，可继续原 Run 或开始新任务。', 'paused', false)
    case 'WAITING_APPROVAL':
      return activity('高风险操作正在等待人工审批，批准后将从当前检查点继续。', 'waiting', false)
    case 'NEEDS_CLARIFICATION':
      return activity('Agent 需要补充信息后才能继续执行。', 'waiting', false)
    case 'BLOCKED':
      return activity('输出已被安全护栏拦截。', 'error', false)
    case 'FAILED':
      return activity('任务执行失败，请结合右侧事件定位失败阶段。', 'error', false)
    case 'REJECTED':
      return activity('人工审批已拒绝，高风险操作未执行。', 'error', false)
    case 'MANUAL_REVIEW':
      return activity('工具执行结果不确定，正在等待人工核对。', 'waiting', false)
    default:
      return null
  }
}

function activityFromEvent(event: AgentStreamEvent): RuntimeActivity | null {
  switch (event.type) {
    case 'run_started':
      return activity('已接收任务，正在建立 Run 并准备上下文…')
    case 'run_resumed':
      return activity('已从检查点恢复，正在继续执行原 Run…')
    case 'run_pause_requested':
      return activity('已收到暂停请求，正在完成当前步骤并保存检查点…', 'waiting')
    case 'run_paused':
      return activity('任务已在安全检查点暂停，可随时恢复。', 'paused', false)
    case 'context_prepared':
      return activity('上下文已组装完成，正在准备模型决策…')
    case 'context_compacted':
      return activity('上下文已安全压缩，正在重新组织模型输入…')
    case 'model_started': {
      const turn = numberMetadata(event, 'turn')
      return activity(turn > 0
        ? `模型正在分析并规划第 ${turn} 轮决策…`
        : '模型正在分析任务并规划下一步…')
    }
    case 'model_delta':
      return activity('模型正在生成回答…')
    case 'model_completed': {
      const toolCallCount = numberMetadata(event, 'toolCallCount')
      return activity(toolCallCount > 0
        ? `模型已选择 ${toolCallCount} 个工具，正在准备调用…`
        : '模型回答已生成，正在执行输出护栏检查…')
    }
    case 'model_failed':
      return activity('模型调用出现异常，Runtime 正在判断是否压缩上下文后重试…', 'waiting')
    case 'tool_requested':
      return activity(`正在校验工具${toolName(event)}的参数与调用权限…`)
    case 'policy_decided': {
      const action = textMetadata(event, 'action').toUpperCase()
      if (action === 'ASK') {
        return activity(`工具${toolName(event)}属于高风险操作，正在创建审批请求…`, 'waiting')
      }
      if (action === 'DENY') {
        return activity(`工具${toolName(event)}已被策略拒绝，正在整理原因…`, 'error')
      }
      return activity(`工具${toolName(event)}已通过策略校验，正在执行…`)
    }
    case 'approval_required':
      return activity(`工具${toolName(event)}正在等待人工审批，批准后将继续当前 Run。`, 'waiting', false)
    case 'tool_started':
      return activity(`正在执行工具${toolName(event)}…`)
    case 'tool_completed':
      return activity(`工具${toolName(event)}执行完成，正在将结果写回上下文…`)
    case 'sub_agent_started':
      return activity('子 Agent 已启动，正在处理委派任务…')
    case 'sub_agent_completed':
      return activity('子 Agent 已完成，正在汇总结果…')
    case 'run_completed':
      return activity('任务已完成，结果已经收敛。', 'done', false)
    case 'run_failed':
      return activity('任务执行失败，请结合右侧事件查看原因。', 'error', false)
    case 'run_cancelled':
      return activity('任务已安全终止。', 'paused', false)
    case 'stream_gap':
      return activity('事件流出现缺口，正在从持久化时间线补拉进度…', 'waiting')
    case 'transport_error':
      return activity('流式连接异常，正在核对服务端 Run 状态…', 'error')
    default:
      return null
  }
}

export function resolveRuntimeActivity(events: AgentStreamEvent[],
                                       runState: string,
                                       running: boolean): RuntimeActivity | null {
  if (!running) {
    const stateActivity = activityFromRunState(runState)
    if (stateActivity) return stateActivity
  }

  const latestEvent = [...events]
    .reverse()
    .find((event) => !ignoredEventTypes.has(event.type))
  if (latestEvent) {
    const eventActivity = activityFromEvent(latestEvent)
    if (eventActivity) return eventActivity
  }

  return running ? activity('正在接收 Runtime 执行进度…') : null
}
