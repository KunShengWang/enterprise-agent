<script setup lang="ts">
import { computed, ref } from 'vue'
import { API_BASE_URL } from '../api/http'
import { apiCatalog, apiModules, type ApiEndpointDefinition } from '../api/catalog'
import PageIntro from '../components/PageIntro.vue'

const moduleFilter = ref('ALL')
const search = ref('')
const selected = ref<ApiEndpointDefinition>(apiCatalog[0]!)
const requestPath = ref(selected.value.examplePath ?? selected.value.path)
const queryText = ref(JSON.stringify(selected.value.query ?? {}, null, 2))
const bodyText = ref(selected.value.body === undefined ? '' : JSON.stringify(selected.value.body, null, 2))
const acknowledged = ref(false)
const responseText = ref('')
const responseStatus = ref('—')
const responseDuration = ref(0)
const running = ref(false)
const error = ref('')
let controller: AbortController | null = null

const filtered = computed(() => apiCatalog.filter((endpoint) => {
  const moduleMatches = moduleFilter.value === 'ALL' || endpoint.module === moduleFilter.value
  const keyword = search.value.trim().toLowerCase()
  return moduleMatches && (!keyword || `${endpoint.title} ${endpoint.path} ${endpoint.description}`.toLowerCase().includes(keyword))
}))

const curlCommand = computed(() => {
  const query = parseObject(queryText.value, false)
  const params = new URLSearchParams()
  if (query) Object.entries(query).forEach(([key, value]) => params.set(key, String(value)))
  const suffix = params.size ? `?${params.toString()}` : ''
  const url = `${API_BASE_URL || 'http://localhost:8083'}${requestPath.value}${suffix}`
  const parts = [`curl -X ${selected.value.method} "${url}"`, '-H "Accept: application/json"']
  if (bodyText.value.trim()) {
    parts.push('-H "Content-Type: application/json"', `--data '${bodyText.value.replace(/\s+/g, ' ')}'`)
  }
  return parts.join(' \\\n  ')
})

function parseObject(text: string, required: boolean): Record<string, unknown> | null {
  if (!text.trim()) return required ? {} : null
  const value = JSON.parse(text) as unknown
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('必须填写 JSON 对象')
  return value as Record<string, unknown>
}

function choose(endpoint: ApiEndpointDefinition) {
  selected.value = endpoint
  requestPath.value = endpoint.examplePath ?? endpoint.path
  queryText.value = JSON.stringify(endpoint.query ?? {}, null, 2)
  bodyText.value = endpoint.body === undefined ? '' : JSON.stringify(endpoint.body, null, 2)
  responseText.value = ''
  responseStatus.value = '—'
  responseDuration.value = 0
  acknowledged.value = false
  error.value = ''
}

async function execute() {
  if (selected.value.sideEffect && !acknowledged.value) {
    error.value = '该接口会修改状态或产生模型调用，请先勾选确认。'
    return
  }
  if (requestPath.value.includes('{') || requestPath.value.includes('替换为')) {
    error.value = '请先把路径中的占位内容替换为真实 ID。'
    return
  }
  running.value = true
  error.value = ''
  responseText.value = ''
  const start = performance.now()
  controller = new AbortController()
  try {
    const query = parseObject(queryText.value, false)
    const params = new URLSearchParams()
    if (query) Object.entries(query).forEach(([key, value]) => params.set(key, String(value)))
    const url = `${API_BASE_URL}${requestPath.value}${params.size ? `?${params}` : ''}`
    const headers = new Headers({ Accept: selected.value.stream ? 'text/event-stream' : 'application/json' })
    const options: RequestInit = { method: selected.value.method, headers, signal: controller.signal }
    if (bodyText.value.trim()) {
      JSON.parse(bodyText.value)
      headers.set('Content-Type', 'application/json')
      options.body = bodyText.value
    }
    const response = await fetch(url, options)
    responseStatus.value = `${response.status} ${response.statusText}`
    if (response.headers.get('content-type')?.includes('text/event-stream') && response.body) {
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      while (true) {
        const { value, done } = await reader.read()
        responseText.value += decoder.decode(value, { stream: !done })
        if (done) break
      }
    } else {
      const raw = await response.text()
      try { responseText.value = JSON.stringify(JSON.parse(raw), null, 2) } catch { responseText.value = raw }
    }
  } catch (reason) {
    if (reason instanceof DOMException && reason.name === 'AbortError') {
      error.value = '请求已取消。'
    } else {
      error.value = reason instanceof Error ? reason.message : '请求执行失败'
    }
  } finally {
    responseDuration.value = Math.round(performance.now() - start)
    running.value = false
    controller = null
  }
}

function cancel() { controller?.abort() }
async function copyCurl() { await window.navigator.clipboard.writeText(curlCommand.value) }
</script>

<template>
  <div class="module-page">
    <PageIntro
      kicker="API EXPLORER"
      title="从 Controller 路由反推后端能力边界"
      description="完整收录当前项目的 HTTP 接口。选择接口后可以修改 Path、Query 和 JSON Body，直接查看 ApiResponse 或 SSE 原始数据。"
      :endpoints="[`${apiCatalog.length} endpoints`, `${apiModules.length - 1} modules`, 'same-origin via Vite proxy']"
    />

    <div class="api-lab-layout">
      <section class="panel api-catalog-panel">
        <div class="api-search"><input v-model="search" placeholder="搜索路径或功能" /><select v-model="moduleFilter"><option v-for="module in apiModules" :key="module">{{ module }}</option></select></div>
        <div class="api-list">
          <button v-for="endpoint in filtered" :key="endpoint.id" type="button" :class="{ selected: selected.id === endpoint.id }" @click="choose(endpoint)">
            <span :class="`method-${endpoint.method.toLowerCase()}`">{{ endpoint.method }}</span>
            <div><strong>{{ endpoint.title }}</strong><code>{{ endpoint.path }}</code></div>
          </button>
        </div>
      </section>

      <section class="panel api-request-panel">
        <div class="api-definition">
          <div><span :class="`method-${selected.method.toLowerCase()}`">{{ selected.method }}</span><p class="eyebrow">{{ selected.module }}</p></div>
          <h3>{{ selected.title }}</h3><p>{{ selected.description }}</p>
        </div>
        <label>Request Path<input v-model="requestPath" /></label>
        <div class="api-editors">
          <label>Query JSON<textarea v-model="queryText" rows="7" spellcheck="false" /></label>
          <label>Body JSON<textarea v-model="bodyText" rows="7" spellcheck="false" placeholder="该接口不需要 Body" /></label>
        </div>
        <label v-if="selected.sideEffect" class="side-effect-confirm"><input v-model="acknowledged" type="checkbox" /><span><strong>确认执行有副作用的接口</strong>可能创建 Run、调用模型、写入或删除数据。</span></label>
        <p v-if="error" class="inline-error">{{ error }}</p>
        <div class="action-row">
          <button v-if="!running" class="primary-button" type="button" @click="execute">发送请求</button>
          <button v-else class="danger-button" type="button" @click="cancel">中止请求</button>
          <button class="secondary-button" type="button" @click="copyCurl">复制 cURL</button>
        </div>
        <div class="curl-preview"><code>{{ curlCommand }}</code></div>
      </section>

      <section class="panel api-response-panel">
        <div><p class="eyebrow">RAW RESPONSE</p><span>{{ responseStatus }}</span><span>{{ responseDuration }}ms</span></div>
        <pre><code>{{ responseText || '发送请求后，原始响应会显示在这里。' }}</code></pre>
      </section>
    </div>
  </div>
</template>
