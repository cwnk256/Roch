<script lang="ts" setup>
import {computed, onMounted, onUnmounted, ref, watch} from 'vue'
import {RouterView, useRoute} from 'vue-router'
import {Bot, Menu, X} from 'lucide-vue-next'
import ColorModeSwitch from './ColorModeSwitch.vue'
import WorkspaceNavigation from './WorkspaceNavigation.vue'

const route = useRoute()
const navigationDialog = ref<HTMLDialogElement | null>(null)
const navigationTrigger = ref<HTMLButtonElement | null>(null)
const pageHeading = ref<HTMLHeadingElement | null>(null)
const navigationOpen = ref(false)
const title = computed(() => String(route.meta.title || '工作台'))

function openNavigation() {
  if (navigationDialog.value && !navigationDialog.value.open) {
    navigationDialog.value.showModal()
    navigationOpen.value = true
  }
}

function closeNavigation(focusTarget: HTMLElement | null = navigationTrigger.value) {
  navigationDialog.value?.close()
  navigationOpen.value = false
  focusTarget?.focus()
}

function selectNavigation(path: string) {
  if (path === route.path) closeNavigation()
}

watch(() => route.path, () => {
  if (navigationDialog.value?.open) {
    closeNavigation(pageHeading.value)
  }
}, {flush: 'post'})

function handleLayoutChange(event: MediaQueryListEvent) {
  if (!event.matches && navigationDialog.value?.open) {
    closeNavigation(pageHeading.value)
  }
}

// 后端服务可达性：定时探测真实业务端点；请求失败/非 2xx 即视为服务不可用
const backendOnline = ref(true)
const HEALTH_URL = '/api/documents?page=0&size=1'
const POLL_INTERVAL_MS = 10000
let healthTimer: number | null = null
let narrowLayout: MediaQueryList | null = null

async function checkHealth() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch(HEALTH_URL, {signal: controller.signal, headers: {Accept: 'application/json'}})
    backendOnline.value = response.ok
  } catch {
    backendOnline.value = false
  } finally {
    clearTimeout(timer)
  }
}

function handleOffline() {
  backendOnline.value = false
}

onMounted(() => {
  checkHealth()
  healthTimer = window.setInterval(checkHealth, POLL_INTERVAL_MS)
  narrowLayout = window.matchMedia('(max-width: 900px)')
  narrowLayout.addEventListener('change', handleLayoutChange)
  window.addEventListener('offline', handleOffline)
  window.addEventListener('online', checkHealth)
})

onUnmounted(() => {
  if (healthTimer !== null) window.clearInterval(healthTimer)
  narrowLayout?.removeEventListener('change', handleLayoutChange)
  window.removeEventListener('offline', handleOffline)
  window.removeEventListener('online', checkHealth)
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar glass-surface">
      <div class="brand">
        <span class="brand-mark"><Bot :size="19" aria-hidden="true"/></span>
        <span>Roch<small>知识工作台</small></span>
      </div>
      <WorkspaceNavigation/>
    </aside>

    <dialog id="workspace-menu" ref="navigationDialog" aria-labelledby="workspace-menu-title"
            class="mobile-navigation glass-surface" @click.self="closeNavigation()" @cancel.prevent="closeNavigation()">
      <div class="mobile-navigation-inner">
        <div class="brand">
          <span class="brand-mark"><Bot :size="19" aria-hidden="true"/></span>
          <span id="workspace-menu-title">主导航</span>
          <button type="button" class="icon-button" aria-label="关闭主导航" autofocus @click="closeNavigation()">
            <X :size="19" aria-hidden="true"/>
          </button>
        </div>
        <WorkspaceNavigation @select="selectNavigation"/>
      </div>
    </dialog>

    <div class="main-column">
      <header class="topbar glass-surface">
        <button ref="navigationTrigger" type="button" class="icon-button menu-button" aria-label="打开主导航" aria-haspopup="dialog"
                aria-controls="workspace-menu" :aria-expanded="navigationOpen" @click="openNavigation">
          <Menu :size="21" aria-hidden="true"/>
        </button>
        <h1 ref="pageHeading" tabindex="-1">{{ title }}</h1>
        <div class="topbar-actions">
          <div class="topbar-status" :class="{'is-offline': !backendOnline}" role="status">
            <span class="status-dot" aria-hidden="true"></span>
            {{ backendOnline ? '服务在线' : '服务不可用' }}
          </div>
          <ColorModeSwitch/>
        </div>
      </header>
      <main class="content">
        <RouterView/>
      </main>
    </div>
  </div>
</template>
