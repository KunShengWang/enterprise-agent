import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const bundled = await build({
  entryPoints: [fileURLToPath(new URL('../utils/textReveal.ts', import.meta.url))],
  bundle: true,
  format: 'esm',
  platform: 'node',
  write: false,
})
const source = Buffer.from(bundled.outputFiles[0].contents).toString('base64')
const { createTextReveal } = await import(`data:text/javascript;base64,${source}`)

/**
 * 手动时钟：帧回调由测试推进，时间完全可控。
 * 生产环境用 requestAnimationFrame，这里换成同步队列，避免真实等待。
 */
function makeClock() {
  let pending = null
  let now = 0
  return {
    scheduler: {
      request(callback) { pending = callback; return 1 },
      cancel() { pending = null },
    },
    /** 推进 ms 毫秒并执行排队的帧回调（回调内会重新入队下一帧）。 */
    advance(ms) {
      now += ms
      const callback = pending
      pending = null
      if (callback) callback(now)
    },
    get scheduled() { return pending !== null },
  }
}

/** 跑 n 帧，每帧 32ms（rAF 在 60Hz 下的实际间隔上限）。 */
function runFrames(clock, n) {
  for (let i = 0; i < n; i += 1) clock.advance(32)
}

const LARGE = 'a'.repeat(4000)

/* ------------------------------------------------------------------ */
/* 1. 大块增量不得被瞬间排干（原实现 4000 字符时 5000 字符/秒 → 0.8 秒到底） */
/* ------------------------------------------------------------------ */
{
  const clock = makeClock()
  const lengths = []
  const reveal = createTextReveal(clock.scheduler, text => lengths.push(text.length))
  reveal.update(LARGE, true)

  runFrames(clock, 31) // ≈1 秒
  const afterOneSecond = lengths.at(-1) ?? 0

  assert.ok(afterOneSecond < 800,
    `1 秒内显示量应远小于全文，实际 ${afterOneSecond}/4000（仍像整块弹出）`)
  assert.ok(afterOneSecond > 5,
    `1 秒内应有可见进展，实际 ${afterOneSecond}`)
  reveal.dispose()
}

/* ------------------------------------------------------------------ */
/* 2. 单帧增量必须有上界——这是「一块一块」的量化反面                   */
/* ------------------------------------------------------------------ */
{
  const clock = makeClock()
  const lengths = []
  const reveal = createTextReveal(clock.scheduler, text => lengths.push(text.length))
  reveal.update(LARGE, true)

  runFrames(clock, 60)
  const deltas = lengths.map((len, i) => (i === 0 ? len : len - lengths[i - 1]))
  const maxDelta = Math.max(...deltas)

  assert.ok(maxDelta <= 24,
    `单帧增量应 ≤24 字符（8 秒预算下 4000 字符的理论值），实际 ${maxDelta}`)
  reveal.dispose()
}

/* ------------------------------------------------------------------ */
/* 3. 8 秒预算：长回答必须在预算内播完，不能无限拖                          */
/* ------------------------------------------------------------------ */
{
  const clock = makeClock()
  const lengths = []
  const reveal = createTextReveal(clock.scheduler, text => lengths.push(text.length))
  reveal.update(LARGE, true)

  runFrames(clock, 270) // ≈8.6 秒
  assert.equal(lengths.at(-1), LARGE.length,
    `8 秒预算后应已显示全文，实际 ${lengths.at(-1)}/${LARGE.length}`)
  reveal.dispose()
}

/* ------------------------------------------------------------------ */
/* 4. 短回答按恒定速率播放，不因量小而变慢或变快                          */
/* ------------------------------------------------------------------ */
{
  const clock = makeClock()
  const lengths = []
  const reveal = createTextReveal(clock.scheduler, text => lengths.push(text.length))
  const short = 'b'.repeat(200)
  reveal.update(short, true)

  runFrames(clock, 31) // ≈1 秒
  const afterOneSecond = lengths.at(-1) ?? 0
  assert.ok(afterOneSecond >= 30 && afterOneSecond <= 90,
    `200 字符的回答 1 秒内应显示约 50 字符，实际 ${afterOneSecond}`)
  reveal.dispose()
}

/* ------------------------------------------------------------------ */
/* 5. 终态 settle：complete 后必须在预算内对齐（保留原有 ~650ms 行为）      */
/* ------------------------------------------------------------------ */
{
  const clock = makeClock()
  const lengths = []
  const reveal = createTextReveal(clock.scheduler, text => lengths.push(text.length))
  const text = 'c'.repeat(3000)
  reveal.update(text, true)
  runFrames(clock, 10) // 先播一小段
  reveal.update(text, true, true) // 终态到达

  runFrames(clock, 25) // ≈800ms
  assert.equal(lengths.at(-1), text.length, '终态后应在 ~650ms 内排干')
  reveal.dispose()
}

/* ------------------------------------------------------------------ */
/* 6. Unicode 代理对不得被截断                                         */
/* ------------------------------------------------------------------ */
{
  const clock = makeClock()
  let latest = ''
  const reveal = createTextReveal(clock.scheduler, text => { latest = text })
  reveal.update('😀'.repeat(50), true)
  runFrames(clock, 200)

  const loneSurrogate = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(latest)
  assert.ok(!loneSurrogate, '显示文本不得出现孤立代理项')
  assert.equal(latest, '😀'.repeat(50), '最终应完整显示全部 emoji')
  reveal.dispose()
}

/* ------------------------------------------------------------------ */
/* 7. 非前缀修正必须立即对齐，不能继续在错误文本上追加                     */
/* ------------------------------------------------------------------ */
{
  const clock = makeClock()
  let latest = ''
  const reveal = createTextReveal(clock.scheduler, text => { latest = text })
  reveal.update('d'.repeat(1000), true)
  runFrames(clock, 5)
  reveal.update('e'.repeat(10), true) // 权威修正：不是原目标的前缀
  assert.equal(latest, 'e'.repeat(10), '非前缀修正应立即整体替换')
  reveal.dispose()
}

console.log('textReveal smoke passed (7 cases: 大块不排干、帧增量上界、8 秒预算、短回答恒定速率、终态 settle、Unicode 代理对、非前缀修正)')
