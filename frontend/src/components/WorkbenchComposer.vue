<script setup lang="ts">
defineProps<{ modelValue: string; busy: boolean; error: string }>()
const emit = defineEmits<{ submit: []; 'update:modelValue': [value: string] }>()
</script>

<template>
  <footer class="task-composer">
    <div><textarea :value="modelValue" rows="2" placeholder="描述目标，或为当前任务补充要求…" @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)" @keydown.ctrl.enter.prevent="emit('submit')" /><button type="button" title="发送" :disabled="busy || !modelValue.trim()" @click="emit('submit')">↑</button></div>
    <p><span v-if="error" class="composer-error">{{ error }}</span><span v-else>Ctrl + Enter 发送 · 运行中的任务可接收补充指令</span></p>
  </footer>
</template>
