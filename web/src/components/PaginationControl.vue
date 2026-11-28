<script lang="ts" setup>
import {computed} from 'vue'
import {ChevronLeft, ChevronRight} from 'lucide-vue-next'
import {getPaginationState} from '../lib/pagination'

const props = defineProps<{ page: number; size: number; total: number }>()
const emit = defineEmits<{ change: [page: number] }>()
const state = computed(() => getPaginationState(props.total, props.size, props.page))
</script>

<template>
  <nav v-if="state.pageCount > 1" aria-label="分页" class="pagination">
    <span>{{ state.from }}–{{ state.to }} 条，共 {{ total }} 条</span>
    <div class="pagination-actions">
      <button :disabled="state.page === 0" class="icon-button" aria-label="上一页" title="上一页"
              @click="emit('change', state.page - 1)">
        <ChevronLeft :size="16" aria-hidden="true"/>
      </button>
      <span>{{ state.page + 1 }} / {{ state.pageCount }}</span>
      <button :disabled="state.page === state.pageCount - 1" class="icon-button" aria-label="下一页" title="下一页"
              @click="emit('change', state.page + 1)">
        <ChevronRight :size="16" aria-hidden="true"/>
      </button>
    </div>
  </nav>
</template>
