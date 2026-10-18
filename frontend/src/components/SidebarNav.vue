<script setup lang="ts">
/**
 * 左栏底部的共享导航块：「更多」折叠菜单 + 设置入口 + 主题切换。
 *
 * 抽出来的动因：AppSidebar（次级页）与 WorkbenchTaskSidebar（工作台）此前各自
 * 内联了一份完全相同的 iconMap 与模板（约 50 行 × 2）。两套左栏的**容器布局**
 * 不同（一个是 .sidebar，一个是 .task-product-nav 栅格），但这一块内容一致，
 * 因此只合并内容块，不动各自的网格。
 *
 * 数据来自 navigation.ts，主题逻辑来自 theme.ts —— 这两个才是单一来源，
 * 本组件只负责渲染。
 */
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  Activity, Blocks, BookOpen, ChevronDown, FlaskConical, History,
  Moon, Settings, ShieldCheck, ShoppingBag, Sun,
} from '@lucide/vue'
import { moreNav, settingsNav } from '../navigation'
import { applyTheme, currentTheme, type Theme } from '../theme'

const emit = defineEmits<{ navigate: [] }>()

const moreOpen = ref(false)
const theme = ref<Theme>(currentTheme())

/** 导航目标 → 图标。新增页面时这里补一行即可，不必两处同步。 */
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
  <div class="sidebar-nav">
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
      <RouterLink v-for="item in moreNav" :key="item.to" :to="item.to" @click="emit('navigate')">
        <span class="nav-code"><component :is="iconFor(item.to)" :size="17" /></span>
        <span class="nav-copy">
          <strong>{{ item.label }}</strong>
          <small>{{ item.hint }}</small>
        </span>
      </RouterLink>
    </nav>

    <div class="sidebar-footer">
      <RouterLink :to="settingsNav.to" class="sidebar-footer-link" @click="emit('navigate')">
        <Settings :size="17" />
        <span>{{ settingsNav.label }}</span>
      </RouterLink>
      <button class="sidebar-footer-link" type="button" :aria-label="themeLabel" :title="themeLabel" @click="toggleTheme">
        <Moon v-if="theme === 'dark'" :size="17" />
        <Sun v-else :size="17" />
        <span>{{ theme === 'dark' ? '深色' : '浅色' }}</span>
      </button>
    </div>
  </div>
</template>
