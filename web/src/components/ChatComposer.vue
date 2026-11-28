<script lang="ts" setup>
import {Send} from 'lucide-vue-next'

defineProps<{ loading: boolean; placeholder: string }>()
const input = defineModel<string>({required: true})
const emit = defineEmits<{ send: [] }>()

function onEnter(event: KeyboardEvent) {
  if (event.isComposing) return
  event.preventDefault()
  emit('send')
}
</script>

<template>
  <form class="chat-compose" @submit.prevent="emit('send')">
    <textarea v-model="input" aria-label="消息内容" :placeholder="placeholder" rows="2" @keydown.enter.exact="onEnter"/>
    <button class="button primary" type="submit" :disabled="loading || !input.trim()">
      <Send :size="16" aria-hidden="true"/>
      发送
    </button>
  </form>
</template>
