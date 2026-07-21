<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { ConversationTurnView, PresentationLocator } from '../types/conversation'
import ConversationTurnSection from './ConversationTurnSection.vue'

const props = defineProps<{
  turns: ConversationTurnView[]
  selectedTurnId: string
  hasWork: boolean
  busy: boolean
  reviewer: string
  decisionReason: string
}>()
const emit = defineEmits<{
  confirmPreview: [approved: boolean]
  decideApproval: [approved: boolean]
  copy: [content: string]
  retry: []
  diagnostics: []
  supplyInput: []
  selectTurn: [turnId: string]
  locatePresentations: [locator: PresentationLocator]
  'update:reviewer': [value: string]
  'update:decisionReason': [value: string]
}>()
const feed = ref<HTMLElement | null>(null)
const followOutput = ref(true)

async function scrollToBottom(force = false) {
  await nextTick()
  if (!force && !followOutput.value) return
  if (feed.value) feed.value.scrollTop = feed.value.scrollHeight
}

function handleScroll() {
  const element = feed.value
  if (!element) return
  followOutput.value = element.scrollHeight - element.scrollTop - element.clientHeight < 96
}

function resumeFollowing() {
  followOutput.value = true
  void scrollToBottom(true)
}

watch(() => [props.turns.length,
  props.turns.at(-1)?.entries.at(-1)?.content.length ?? 0], () => {
  if (followOutput.value) void scrollToBottom()
})
</script>

<template>
  <section ref="feed" class="task-conversation-feed" @scroll.passive="handleScroll">
    <div v-if="!hasWork" class="task-welcome"><span>A</span><h2>今天要完成什么？</h2><p>直接描述目标。普通问答、OrderCare、事故调查和恢复规划都从这里开始。</p></div>
    <div v-else class="conversation-stream">
      <ConversationTurnSection v-for="(turn, index) in turns" :key="turn.turn.turnId"
        :view="turn" :index="index" :selected="turn.turn.turnId === selectedTurnId"
        :busy="busy" :reviewer="reviewer" :decision-reason="decisionReason"
        @select="emit('selectTurn', $event)"
        @update:reviewer="emit('update:reviewer', $event)"
        @update:decision-reason="emit('update:decisionReason', $event)"
        @confirm-preview="emit('confirmPreview', $event)"
        @decide-approval="emit('decideApproval', $event)" @copy="emit('copy', $event)"
        @retry="emit('retry')" @diagnostics="emit('diagnostics')"
        @supply-input="emit('supplyInput')"
        @locate-presentations="emit('locatePresentations', $event)" />
      <article v-if="busy && !turns.length" class="conversation-loading"><span>A</span><div><i /><i /><i /><small>正在理解目标并选择执行方式</small></div></article>
    </div>
  </section>
  <button v-if="!followOutput" class="follow-output-button" type="button" @click="resumeFollowing">回到底部 ↓</button>
</template>
