<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { PanelLeftClose, Plus, ShoppingBag } from '@lucide/vue'
import { primaryNav } from '../navigation'
import SidebarNav from './SidebarNav.vue'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; newTask: [] }>()
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

    <!-- 「更多」+ 设置 + 主题切换与工作台共用同一组件，避免两处模板漂移 -->
    <div class="nav-more">
      <SidebarNav @navigate="emit('close')" />
    </div>
  </aside>
</template>
