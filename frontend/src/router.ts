import { createRouter, createWebHashHistory } from 'vue-router'
import RuntimeWorkbench from './views/RuntimeWorkbench.vue'
import RunHistoryView from './views/RunHistoryView.vue'
import ApprovalCenterView from './views/ApprovalCenterView.vue'
import CapabilitiesView from './views/CapabilitiesView.vue'
import KnowledgeMemoryView from './views/KnowledgeMemoryView.vue'
import ObservabilityView from './views/ObservabilityView.vue'
import ApiLabView from './views/ApiLabView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'runtime', component: RuntimeWorkbench, meta: { title: 'Agent 运行台' } },
    { path: '/runs', name: 'runs', component: RunHistoryView, meta: { title: 'Run 历史与回放' } },
    { path: '/approvals', name: 'approvals', component: ApprovalCenterView, meta: { title: '人工审批中心' } },
    { path: '/capabilities', name: 'capabilities', component: CapabilitiesView, meta: { title: 'Tool 与 Skill 能力地图' } },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeMemoryView, meta: { title: 'RAG 与 Memory 实验室' } },
    { path: '/observability', name: 'observability', component: ObservabilityView, meta: { title: 'Trace · Eval · AgentOps' } },
    { path: '/api-lab', name: 'api-lab', component: ApiLabView, meta: { title: '后端接口实验室' } },
  ],
})

export default router
