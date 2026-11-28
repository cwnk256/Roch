<script lang="ts" setup>
import {Inbox} from 'lucide-vue-next'

defineProps<{ title: string; message?: string }>()
</script>
<template>
  <div class="empty-state">
    <Inbox :size="24" aria-hidden="true"/>
    <strong>{{ title }}</strong>
    <span v-if="message">{{ message }}</span>
  </div>
</template>
