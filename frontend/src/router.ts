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
    { path: '/', name: 'runtime', component: RuntimeWorkbench },
    { path: '/runs', name: 'runs', component: RunHistoryView },
    { path: '/approvals', name: 'approvals', component: ApprovalCenterView },
    { path: '/capabilities', name: 'capabilities', component: CapabilitiesView },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeMemoryView },
    { path: '/observability', name: 'observability', component: ObservabilityView },
    { path: '/api-lab', name: 'api-lab', component: ApiLabView },
  ],
})

export default router
