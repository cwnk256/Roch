<script lang="ts" setup>
defineProps<{ value?: string }>()

const labels: Record<string, string> = {
  COMPLETED: '已完成', RUNNING: '进行中', PENDING: '待处理', FAILED: '失败', READY: '就绪'
}
</script>
<template>
  <span :class="`status-${(value || 'unknown').toLowerCase()}`" class="status-badge">
    {{ value ? labels[value] || value : '未知' }}
  </span>
</template>
