export type PausedRunIntent = 'RESUME' | 'ABANDON' | 'NEW_TASK' | 'AMBIGUOUS'

function compact(value: string) {
  return value.trim().toLowerCase().replace(/[\s。.!！?？、，,：:；;“”"'（）()]/g, '')
}

/**
 * 暂停态输入只做生命周期意图识别，不让模型决定是否取消或复用 Run。
 * 高置信度继续/放弃直接执行；同时包含继续与新约束时交给用户二次选择。
 */
export function classifyPausedRunInput(value: string): PausedRunIntent {
  const text = compact(value)
  if (!text) return 'NEW_TASK'

  const negativeResume = /(不想|不要|不用|别)(再)?(继续|恢复|接着|续跑)/.test(text)
  const negativeCurrentTask = /(不想|不要|不用|别)(再)?(执行|做)(这个|当前|刚才|原来|之前的)?任务/.test(text)
  const explicitAbandon = /(取消|算了|不做了|停止任务|结束任务|放弃任务|终止任务|abortrun|cancelrun)$/.test(text)
    || /^(取消|算了|不做了|停止|结束|放弃|abort|cancel)(吧|了|这个任务|当前任务|原任务)?$/.test(text)
  if (negativeResume || negativeCurrentTask || explicitAbandon) {
    return 'ABANDON'
  }

  const hasResumeVerb = /(继续|接着|恢复|续跑|resume|carryon)/.test(text)
  if (!hasResumeVerb) {
    return 'NEW_TASK'
  }

  const referencesPreviousRun = /(刚才|之前|上次|上一个|原来|原先|原任务|当前任务|这个任务|该任务|断点|暂停|中断|未完成|剩下|原run|之前的run|上次的run)/.test(text)
  const introducesNewTask = /(新任务|新的任务|新需求|新的需求|另一个|另外一个|换一个|重新开始一个|重新提一个)/.test(text)
  const changesInstruction = /(但是|不过|同时|顺便|并且|而且|另外|改成|改为|不要|别|只要|只是|先别|先不)/.test(text)
  if (introducesNewTask || changesInstruction) {
    return 'AMBIGUOUS'
  }

  const controlOnly = text
    .replace(/^(请你|麻烦你|请|麻烦|现在|我想|我要|我希望|帮我|可以|能不能)+/, '')
    .replace(/(谢谢|可以吗|好吗|一下|吧|了)+$/, '')
  const pureResumeControl = /^(继续|接着|恢复|续跑|resume|carryon)(执行|运行|做|任务|原任务|当前任务|这个任务|刚才任务|刚才的任务|之前任务|之前的任务|上一个任务|原run|当前run)?$/.test(controlOnly)

  if (referencesPreviousRun || pureResumeControl) {
    return 'RESUME'
  }
  return 'AMBIGUOUS'
}
