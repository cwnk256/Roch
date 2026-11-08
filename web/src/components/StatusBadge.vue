<script lang="ts" setup>
defineProps<{ value?: string }>()
</script>
<template><span :class="`status-${(value || 'unknown').toLowerCase()}`" class="status-badge">{{
    value || 'UNKNOWN'
  }}</span></template>
