<script setup lang="ts">
import { ref } from 'vue'
import { Check, Moon, Sun } from '@lucide/vue'
import { applyTheme, currentTheme, type Theme } from '../theme'

const active = ref<Theme>(currentTheme())

const options: Array<{ value: Theme; label: string; hint: string; icon: unknown }> = [
  { value: 'light', label: '浅色', hint: '当前默认主题', icon: Sun },
  { value: 'dark', label: '深色', hint: '实验性——部分页面仍有未迁移的浅色块', icon: Moon },
]

function choose(theme: Theme) {
  active.value = theme
  applyTheme(theme)
}
</script>

<template>
  <section class="settings-page">
    <header class="page-intro">
      <p class="eyebrow">Preferences</p>
      <h1>设置</h1>
      <p>界面偏好立即生效并保存在本地。Agent 执行参数为只读展示，将在后续波次加入。</p>
    </header>

    <section class="panel settings-panel">
      <h2>主题</h2>
      <div class="theme-options">
        <button
          v-for="option in options"
          :key="option.value"
          class="theme-option"
          :class="{ selected: active === option.value }"
          type="button"
          :aria-pressed="active === option.value"
          @click="choose(option.value)"
        >
          <component :is="option.icon" :size="18" />
          <span>
            <strong>{{ option.label }}</strong>
            <small>{{ option.hint }}</small>
          </span>
          <Check v-if="active === option.value" :size="16" class="theme-check" />
        </button>
      </div>
    </section>

    <section class="panel settings-panel">
      <h2>即将加入</h2>
      <ul class="settings-upcoming">
        <li>Agent 执行配置只读快照（模型、超时、工具策略）</li>
        <li>打字机输出速度</li>
        <li>信息密度</li>
      </ul>
    </section>
  </section>
</template>

<style scoped>
.settings-page { display: grid; gap: 16px; }
.settings-panel { padding: 20px 22px; }
.settings-panel h2 { margin: 0 0 14px; font-size: var(--font-section); }
.theme-options { display: grid; gap: 8px; max-width: 420px; }
.theme-option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--panel);
  color: var(--text);
  text-align: left;
  cursor: pointer;
}
.theme-option:hover { border-color: var(--muted); }
.theme-option.selected { border-color: var(--accent); }
.theme-option span { display: grid; gap: 2px; min-width: 0; }
.theme-option strong { font-size: var(--font-meta); }
.theme-option small { color: var(--muted); font-size: var(--font-caption); }
.theme-check { margin-left: auto; color: var(--accent); }
.settings-upcoming { margin: 0; padding-left: 20px; color: var(--muted); line-height: 1.8; }
</style>
