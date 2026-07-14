<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { agentApi } from '../api/agent'
import JsonViewer from '../components/JsonViewer.vue'
import PageIntro from '../components/PageIntro.vue'
import type { MemorySearchResult, RagResult, UserProfile } from '../types/agent'

type Tab = 'rag' | 'memory'
const activeTab = ref<Tab>('rag')
const loading = ref(false)
const error = ref('')

const ragQuery = ref('发布失败时应该先检查什么？')
const topK = ref(3)
const ragResult = ref<RagResult | null>(null)
const ragStats = ref<Record<string, unknown>>({})
const ragRunStats = ref<Record<string, unknown>>({})
const cacheStats = ref<Record<string, unknown>>({})

const conversationId = ref('conversation-1')
const memoryUserId = ref('student-001')
const memoryQuery = ref('用户偏好和历史信息')
const memories = ref<MemorySearchResult[]>([])
const profile = ref<UserProfile | null>(null)
const profileKey = ref('preferred_language')
const profileValue = ref('Java')

async function loadStats() {
  try {
    const [corpus, runs, cache] = await Promise.all([
      agentApi.ragStats(), agentApi.ragRunStats(), agentApi.ragCacheStats(),
    ])
    ragStats.value = corpus
    ragRunStats.value = runs
    cacheStats.value = cache
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'RAG 统计加载失败'
  }
}

async function searchRag() {
  loading.value = true
  error.value = ''
  try {
    ragResult.value = await agentApi.ragSearch(ragQuery.value.trim(), topK.value)
    await loadStats()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'RAG 检索失败'
  } finally {
    loading.value = false
  }
}

async function recall() {
  loading.value = true
  error.value = ''
  try {
    memories.value = await agentApi.recallMemory(
      conversationId.value.trim(), memoryUserId.value.trim(), memoryQuery.value.trim(), 8,
    )
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'Memory 召回失败'
  } finally {
    loading.value = false
  }
}

async function loadProfile() {
  loading.value = true
  error.value = ''
  try {
    profile.value = await agentApi.userProfile(memoryUserId.value.trim())
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '用户画像加载失败'
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  loading.value = true
  error.value = ''
  try {
    profile.value = await agentApi.upsertUserProfile(
      memoryUserId.value.trim(), profileKey.value.trim(), profileValue.value.trim(), 'learning-console',
    )
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '画像写入失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadStats)
</script>

<template>
  <div class="module-page">
    <PageIntro
      kicker="KNOWLEDGE & MEMORY"
      title="区分外部知识与用户长期状态"
      description="RAG 从知识语料检索事实证据；Memory 保存跨轮次的会话信息与用户画像。它们最终都由 Context Manager 投影进模型上下文。"
      :endpoints="['POST /api/agent/rag/search', 'GET /api/agent/rag/stats', 'GET /api/agent/memory/conversations/{id}/recall', 'GET /api/agent/memory/users/{id}/profile']"
    />
    <p v-if="error" class="inline-error">{{ error }}</p>

    <section class="panel knowledge-panel">
      <div class="module-tabs padded-tabs">
        <button :class="{ active: activeTab === 'rag' }" @click="activeTab = 'rag'">RAG 检索实验</button>
        <button :class="{ active: activeTab === 'memory' }" @click="activeTab = 'memory'">Memory 召回实验</button>
      </div>

      <div v-if="activeTab === 'rag'" class="lab-split">
        <div class="lab-form">
          <p class="eyebrow">RETRIEVAL INPUT</p>
          <h3>绕过 Agent，单独测试检索链</h3>
          <label>查询文本<textarea v-model="ragQuery" rows="5" /></label>
          <label>Top K<input v-model.number="topK" type="number" min="1" max="20" /></label>
          <button class="primary-button" type="button" :disabled="loading" @click="searchRag">执行混合检索</button>
          <div class="mini-stats">
            <JsonViewer :value="ragStats" label="语料统计" />
            <JsonViewer :value="ragRunStats" label="检索统计" />
            <JsonViewer :value="cacheStats" label="缓存统计" />
          </div>
        </div>
        <div class="lab-results">
          <div v-if="ragResult" class="retrieval-summary">
            <div><span>MODE</span><strong>{{ ragResult.retrievalMode }}</strong></div>
            <div><span>EVIDENCE</span><strong>{{ ragResult.enoughEvidence ? 'ENOUGH' : 'WEAK' }}</strong></div>
            <div><span>DURATION</span><strong>{{ ragResult.durationMs }}ms</strong></div>
            <div><span>DOCS</span><strong>{{ ragResult.documents.length }}</strong></div>
          </div>
          <article v-for="(document, index) in ragResult?.documents ?? []" :key="document.documentId" class="document-card">
            <div><span>#{{ index + 1 }}</span><strong>{{ document.score.toFixed(4) }}</strong></div>
            <h4>{{ document.title || document.documentId }}</h4>
            <p>{{ document.content }}</p>
            <JsonViewer :value="document.metadata" label="文档 metadata" />
          </article>
          <div v-if="!ragResult" class="detail-empty"><span>⌕</span><strong>等待一次检索</strong><p>结果会展示召回分数、证据门槛和检索模式。</p></div>
          <div v-else-if="!ragResult.documents.length" class="compact-empty">检索完成，但没有文档通过阈值。</div>
        </div>
      </div>

      <div v-else class="memory-lab">
        <div class="memory-forms">
          <div class="lab-form">
            <p class="eyebrow">MEMORY RECALL</p><h3>按 Session、User 与 Query 召回</h3>
            <label>conversationId<input v-model="conversationId" /></label>
            <label>userId<input v-model="memoryUserId" /></label>
            <label>召回查询<textarea v-model="memoryQuery" rows="3" /></label>
            <button class="primary-button" type="button" :disabled="loading" @click="recall">召回长期记忆</button>
          </div>
          <div class="lab-form">
            <p class="eyebrow">USER PROFILE</p><h3>查看或写入结构化画像</h3>
            <label>画像 Key<input v-model="profileKey" /></label>
            <label>画像 Value<input v-model="profileValue" /></label>
            <div class="action-row">
              <button class="secondary-button" type="button" :disabled="loading" @click="loadProfile">读取画像</button>
              <button class="primary-button" type="button" :disabled="loading" @click="saveProfile">写入画像</button>
            </div>
          </div>
        </div>
        <div class="memory-results">
          <article v-for="memory in memories" :key="memory.id" class="memory-card">
            <div><span>{{ memory.type }}</span><strong>{{ memory.score.toFixed(4) }}</strong></div>
            <p>{{ memory.content }}</p>
            <JsonViewer :value="memory.metadata" label="召回元数据" />
          </article>
          <div v-if="!memories.length" class="compact-empty">尚未执行 Memory Recall</div>
          <JsonViewer v-if="profile" :value="profile" :collapsed="false" label="UserProfile" />
        </div>
      </div>
    </section>
  </div>
</template>
