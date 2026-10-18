export interface RevealScheduler {
  request(callback: (time: number) => void): number
  cancel(id: number): void
}

/**
 * 基础速率：字符/秒。**不随积压量变化**——这是「打字机」体感的唯一来源。
 *
 * 旧实现用 `Math.max(80, remaining / .8)`，速率随未显示字数线性上升：
 * 后端单个 4000 字符增量会让速率变成 5000 字符/秒，0.8 秒排干，
 * 视觉上就是「一整块啪地出现」。速率与积压量解耦后，这个问题从根上消失。
 */
const BASE_CHARS_PER_SECOND = 50

/** 单条回答的显示预算。超出后按剩余量反推「刚好赶上」的最低速率，避免长文播太久。 */
const MAX_REVEAL_MS = 8000

/** 终态排干窗口：权威结果已到、显示仍在追赶时，在此窗口内对齐。 */
const SETTLE_MS = 650

/** rAF 在 60Hz 下的最小有效帧间隔。 */
const MIN_FRAME_MS = 32

/** Display-only animation; the original SSE accumulator never passes through this buffer. */
export function createTextReveal(scheduler: RevealScheduler, publish: (text: string) => void) {
  let target = ''
  let displayed = ''
  let frame: number | null = null
  let lastFrame = 0
  let disposed = false
  let settling = false
  let settleStartedAt: number | null = null
  /** 本条回答开始播放的时间，用于计算 8 秒预算还剩多少。 */
  let revealStartedAt: number | null = null

  function stop() {
    if (frame !== null) scheduler.cancel(frame)
    frame = null
    lastFrame = 0
    settling = false
    settleStartedAt = null
    revealStartedAt = null
  }

  function tick(time: number) {
    frame = null
    if (disposed) return
    const elapsed = lastFrame ? Math.min(64, time - lastFrame) : MIN_FRAME_MS
    if (elapsed < MIN_FRAME_MS) { frame = scheduler.request(tick); return }
    lastFrame = time
    if (revealStartedAt === null) revealStartedAt = time

    const remaining = target.length - displayed.length
    if (settling && settleStartedAt === null) settleStartedAt = time

    let charsPerSecond: number
    if (settling) {
      // 终态：在 SETTLE_MS 内排干，保持原有的「已完成则快速收尾」语义。
      const settleRemainingMs = Math.max(MIN_FRAME_MS, SETTLE_MS - (time - (settleStartedAt ?? time)))
      charsPerSecond = (remaining / settleRemainingMs) * 1000
    } else {
      // 正常播放：默认恒定速率；只有当剩余量在预算内播不完时，
      // 才提升到「刚好赶上预算」的速率。速率只取决于剩余量与剩余预算，
      // 所以大块增量不会再触发速率爆炸。
      const budgetLeftMs = Math.max(MIN_FRAME_MS, MAX_REVEAL_MS - (time - revealStartedAt))
      charsPerSecond = Math.max(BASE_CHARS_PER_SECOND, (remaining / budgetLeftMs) * 1000)
    }

    const count = Math.max(1, Math.ceil(charsPerSecond * elapsed / 1000))
    let end = Math.min(target.length, displayed.length + count)
    // UTF-16 代理对不能被切成两半：如果切点是高代理，就多吞一个单元。
    if (end < target.length && /[\uD800-\uDBFF]/.test(target[end - 1])) end += 1
    displayed = target.slice(0, end)
    publish(displayed)
    if (end < target.length) frame = scheduler.request(tick)
    else lastFrame = 0
  }

  function update(text: string, animate: boolean, complete = false) {
    if (disposed) return
    const correction = !text.startsWith(target)
    target = text
    if (!animate || correction) {
      // 权威修正是非前缀时，继续在错误文本上追加只会放大错误：立即整体对齐。
      stop()
      displayed = text
      publish(displayed)
    } else {
      if (complete && !settling) { settling = true; settleStartedAt = null }
      if (displayed !== target && frame === null) frame = scheduler.request(tick)
    }
  }

  return {
    update,
    get sourceText() { return target },
    get displayText() { return displayed },
    get pendingText() { return target.slice(displayed.length) },
    reset() { stop(); target = ''; displayed = ''; publish('') },
    dispose() { disposed = true; stop(); target = ''; displayed = '' },
  }
}
