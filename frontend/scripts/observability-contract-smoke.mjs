import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
const view = await readFile(new URL('../src/views/ObservabilityView.vue', import.meta.url), 'utf8')
const api = await readFile(new URL('../src/api/agent.ts', import.meta.url), 'utf8')
const catalog = await readFile(new URL('../src/api/catalog.ts', import.meta.url), 'utf8')
assert.doesNotMatch(view, /runEval|actionBusy|latestEvalResult|运行回归评测|运行对抗评测/)
assert.doesNotMatch(api + catalog, /evals\/(regression|adversarial|run)['"`]/)
assert.doesNotMatch(api + catalog, /multi-agent\/runs/)
assert.match(view, /评测通过内部工具执行/)
for (const name of ['evalReports', 'evalEvents', 'traces', 'traceStats', 'traceReplay', 'opsSummary']) {
  assert.ok(view.includes('agentApi.' + name), name + ' must remain available')
  assert.ok(api.includes(name + ':'), name + ' API must remain available')
}
console.log('Observability contract smoke passed: no public execution; report, event, Trace and replay reads retained')
