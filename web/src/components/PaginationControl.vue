<script lang="ts" setup>
import {computed} from 'vue'
import {ChevronLeft, ChevronRight} from 'lucide-vue-next'
import {getPaginationState} from '../lib/pagination'

const props = defineProps<{ page: number; size: number; total: number }>()
const emit = defineEmits<{ change: [page: number] }>()
const state = computed(() => getPaginationState(props.total, props.size, props.page))
</script>

<template>
  <nav v-if="state.pageCount > 1" aria-label="Pagination" class="pagination">
    <span>Showing {{ state.from }}-{{ state.to }} of {{ total }}</span>
    <div class="pagination-actions">
      <button :disabled="state.page === 0" class="icon-button" title="Previous page"
              @click="emit('change', state.page - 1)">
        <ChevronLeft :size="16"/>
      </button>
      <span>{{ state.page + 1 }} / {{ state.pageCount }}</span>
      <button :disabled="state.page === state.pageCount - 1" class="icon-button" title="Next page"
              @click="emit('change', state.page + 1)">
        <ChevronRight :size="16"/>
      </button>
    </div>
  </nav>
</template>
