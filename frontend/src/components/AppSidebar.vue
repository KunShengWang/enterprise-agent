<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  Activity, Blocks, BookOpen, ChevronDown, FlaskConical, History,
  Moon, PanelLeftClose, Plus, Settings, ShieldCheck, ShoppingBag, Sun,
} from '@lucide/vue'
import { moreNav, primaryNav, settingsNav } from '../navigation'
import { applyTheme, currentTheme, type Theme } from '../theme'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; newTask: [] }>()

const moreOpen = ref(false)
const theme = ref<Theme>(currentTheme())

const iconMap: Record<string, unknown> = {
  '/approvals': ShieldCheck,
  '/capabilities': Blocks,
  '/knowledge': BookOpen,
  '/observability': Activity,
  '/api-lab': FlaskConical,
  '/runs': History,
}

const themeLabel = computed(() => (theme.value === 'dark' ? '切换到浅色主题' : '切换到深色主题'))

function iconFor(to: string) {
  return iconMap[to] ?? ShoppingBag
}

function toggleTheme() {
  const next: Theme = theme.value === 'dark' ? 'light' : 'dark'
  theme.value = next
  applyTheme(next)
}
</script>

<template>
  <div v-if="open" class="sidebar-scrim" @click="emit('close')" />
  <aside class="sidebar" :class="{ 'is-open': open }">
    <div class="brand-block">
      <div class="brand-mark"><ShoppingBag :size="18" /></div>
      <div class="brand-copy">
        <strong>Enterprise-Agent</strong>
        <span>采购决策工作台</span>
      </div>
      <button class="icon-button sidebar-close" type="button" aria-label="关闭导航" @click="emit('close')">
        <PanelLeftClose :size="18" />
      </button>
    </div>

    <button class="new-task-button" type="button" @click="emit('newTask')">
      <Plus :size="18" />
      新建对话
    </button>

    <nav class="nav-list" aria-label="主导航">
      <RouterLink :to="primaryNav.to" @click="emit('close')">
        <span class="nav-code"><ShoppingBag :size="17" /></span>
        <span class="nav-copy">
          <strong>{{ primaryNav.label }}</strong>
          <small>{{ primaryNav.hint }}</small>
        </span>
      </RouterLink>
    </nav>

    <div class="nav-more">
      <button
        class="nav-more-toggle"
        type="button"
        :aria-expanded="moreOpen"
        aria-controls="sidebar-more-nav"
        @click="moreOpen = !moreOpen"
      >
        <ChevronDown :size="15" class="nav-more-chevron" :class="{ 'is-open': moreOpen }" />
        <span>更多</span>
        <small>{{ moreNav.length }}</small>
      </button>
      <nav v-if="moreOpen" id="sidebar-more-nav" class="nav-list nav-list-more" aria-label="更多页面">
        <RouterLink v-for="item in moreNav" :key="item.to" :to="item.to" @click="emit('close')">
          <span class="nav-code"><component :is="iconFor(item.to)" :size="17" /></span>
          <span class="nav-copy">
            <strong>{{ item.label }}</strong>
            <small>{{ item.hint }}</small>
          </span>
        </RouterLink>
      </nav>
    </div>

    <div class="sidebar-footer">
      <RouterLink :to="settingsNav.to" class="sidebar-footer-link" @click="emit('close')">
        <Settings :size="17" />
        <span>{{ settingsNav.label }}</span>
      </RouterLink>
      <button class="sidebar-footer-link" type="button" :aria-label="themeLabel" :title="themeLabel" @click="toggleTheme">
        <Moon v-if="theme === 'dark'" :size="17" />
        <Sun v-else :size="17" />
        <span>{{ theme === 'dark' ? '深色' : '浅色' }}</span>
      </button>
    </div>
  </aside>
</template>
