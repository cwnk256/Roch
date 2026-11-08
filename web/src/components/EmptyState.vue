<script lang="ts" setup>
import {Inbox} from 'lucide-vue-next'

defineProps<{ title: string; message?: string }>()
</script>
<template>
  <div class="empty-state">
    <Inbox :size="24"/>
    <strong>{{ title }}</strong><span>{{ message || '暂无数据' }}</span></div>
</template>
