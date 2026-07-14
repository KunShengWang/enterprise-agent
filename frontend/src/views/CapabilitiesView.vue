<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { agentApi } from '../api/agent'
import JsonViewer from '../components/JsonViewer.vue'
import PageIntro from '../components/PageIntro.vue'
import type { SkillDefinition, ToolDefinition } from '../types/agent'

type Tab = 'tools' | 'skills' | 'runs'

const activeTab = ref<Tab>('tools')
const tools = ref<ToolDefinition[]>([])
const skills = ref<SkillDefinition[]>([])
const toolRuns = ref<Array<Record<string, unknown>>>([])
const toolStats = ref<Record<string, unknown>>({})
const loading = ref(false)
const error = ref('')
const search = ref('')

const filteredTools = computed(() => tools.value.filter((tool) =>
  `${tool.name} ${tool.description} ${tool.riskLevel}`.toLowerCase().includes(search.value.toLowerCase()),
))
const filteredSkills = computed(() => skills.value.filter((skill) =>
  `${skill.name} ${skill.description}`.toLowerCase().includes(search.value.toLowerCase()),
))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [toolList, skillList, runs, stats] = await Promise.all([
      agentApi.tools(), agentApi.skills(), agentApi.toolRuns(50), agentApi.toolStats(),
    ])
    tools.value = toolList
    skills.value = skillList
    toolRuns.value = runs
    toolStats.value = stats
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '能力数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="module-page">
    <PageIntro
      kicker="CAPABILITY REGISTRY"
      title="模型只能请求，Runtime 决定能否执行"
      description="ToolDefinition 告诉模型有哪些能力和参数；Skill 是上下文级方法说明。真正的权限、审批、幂等与执行由 Runtime 控制。"
      :endpoints="['GET /api/agent/tools', 'GET /api/agent/skills', 'GET /api/agent/tools/runs', 'GET /api/agent/tools/runs/stats']"
    >
      <button class="secondary-button" type="button" :disabled="loading" @click="load">刷新能力</button>
    </PageIntro>
    <p v-if="error" class="inline-error">{{ error }}</p>

    <section class="panel capability-panel">
      <div class="tab-toolbar">
        <div class="module-tabs">
          <button :class="{ active: activeTab === 'tools' }" @click="activeTab = 'tools'">Tools <span>{{ tools.length }}</span></button>
          <button :class="{ active: activeTab === 'skills' }" @click="activeTab = 'skills'">Skills <span>{{ skills.length }}</span></button>
          <button :class="{ active: activeTab === 'runs' }" @click="activeTab = 'runs'">执行证据 <span>{{ toolRuns.length }}</span></button>
        </div>
        <input v-if="activeTab !== 'runs'" v-model="search" placeholder="搜索名称、描述或风险等级" />
      </div>

      <div v-if="activeTab === 'tools'" class="capability-grid">
        <article v-for="tool in filteredTools" :key="tool.name" class="capability-card">
          <div class="capability-card-top"><span>TOOL</span><strong :class="`risk-${String(tool.riskLevel).toLowerCase()}`">{{ tool.riskLevel }}</strong></div>
          <h3>{{ tool.name }}</h3>
          <p>{{ tool.description }}</p>
          <JsonViewer :value="tool.inputSchema" label="inputSchema" />
          <JsonViewer :value="tool" label="完整 ToolDefinition" />
        </article>
        <div v-if="!filteredTools.length" class="compact-empty">{{ loading ? '正在读取 ToolRegistry…' : '没有匹配的工具' }}</div>
      </div>

      <div v-else-if="activeTab === 'skills'" class="capability-grid">
        <article v-for="skill in filteredSkills" :key="skill.name" class="capability-card skill-card">
          <div class="capability-card-top"><span>SKILL</span><strong>CONTEXT</strong></div>
          <h3>{{ skill.name }}</h3>
          <p>{{ skill.description }}</p>
          <JsonViewer :value="skill" label="完整 SkillDefinition" />
        </article>
        <div v-if="!filteredSkills.length" class="compact-empty">{{ loading ? '正在读取 SkillRegistry…' : '没有匹配的 Skill' }}</div>
      </div>

      <div v-else class="evidence-layout">
        <div class="stats-card"><p class="eyebrow">TOOL RUN STATS</p><JsonViewer :value="toolStats" :collapsed="false" label="聚合统计" /></div>
        <div class="record-stack">
          <article v-for="(run, index) in toolRuns" :key="String(run.toolCallId ?? index)" class="data-record">
            <div><span>{{ run.toolName ?? 'tool' }}</span><code>{{ run.toolCallId ?? run.requestId ?? `record-${index}` }}</code></div>
            <JsonViewer :value="run" label="执行详情" />
          </article>
          <div v-if="!toolRuns.length" class="compact-empty">尚无工具执行证据</div>
        </div>
      </div>
    </section>
  </div>
</template>
