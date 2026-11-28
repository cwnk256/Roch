<script lang="ts" setup>
import {computed, onMounted, onUnmounted, ref, watch} from 'vue'
import {Moon, Sun} from 'lucide-vue-next'

const storageKey = 'roch.colorMode'
const systemMode = window.matchMedia('(prefers-color-scheme: dark)')
const systemIsDark = ref(systemMode.matches)
const preference = ref(readPreference())
const isDark = computed(() => preference.value === null ? systemIsDark.value : preference.value === 'dark')

function readPreference(): 'light' | 'dark' | null {
  try {
    const storedMode = window.localStorage.getItem(storageKey)
    return storedMode === 'light' || storedMode === 'dark' ? storedMode : null
  } catch {
    return null
  }
}

watch(isDark, (dark) => {
  document.documentElement.dataset.colorMode = dark ? 'dark' : 'light'
}, {immediate: true, flush: 'sync'})

function handleSystemModeChange(event: MediaQueryListEvent) {
  systemIsDark.value = event.matches
}

function toggleColorMode() {
  const nextMode = isDark.value ? 'light' : 'dark'
  preference.value = nextMode
  try {
    window.localStorage.setItem(storageKey, nextMode)
  } catch {
    // 存储受限时，仍可在当前页面切换。
  }
}

onMounted(() => systemMode.addEventListener('change', handleSystemModeChange))
onUnmounted(() => systemMode.removeEventListener('change', handleSystemModeChange))
</script>

<template>
  <button type="button" class="icon-button color-mode-switch" role="switch" aria-label="深色模式"
          :aria-checked="isDark" :title="isDark ? '切换到浅色模式' : '切换到深色模式'" @click="toggleColorMode">
    <Moon v-if="isDark" :size="17" aria-hidden="true"/>
    <Sun v-else :size="17" aria-hidden="true"/>
  </button>
</template>

<style scoped>
.color-mode-switch {
  border-radius: var(--radius-pill);
  border-color: var(--line);
  background: var(--surface);
  color: var(--accent);
}

@media (max-width: 900px) {
  .color-mode-switch { min-width: 44px; min-height: 44px }
}
</style>
