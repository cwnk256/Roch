<script lang="ts" setup>
import {computed, onMounted, onUnmounted, ref} from 'vue'
import {RouterLink, RouterView, useRoute} from 'vue-router'
import {
  BarChart3,
  Bot,
  Files,
  FileSearch,
  FileUp,
  Globe,
  Menu,
  MessageCircle,
  MessagesSquare,
  PanelLeftClose,
  ScrollText,
  X
} from 'lucide-vue-next'

const route = useRoute();
const open = ref(false)
const items = [
  {to: '/dashboard', label: '仪表盘', icon: BarChart3}, {to: '/chat', label: '单轮问答', icon: MessageCircle},
  {to: '/conversation', label: '多轮问答', icon: MessagesSquare}, {to: '/search', label: '文件检索', icon: FileSearch},
  {to: '/import', label: '导入文档', icon: FileUp}, {to: '/webpage', label: '获取外部网页', icon: Globe},
  {to: '/documents', label: '文档管理', icon: Files}, {
    to: '/reports',
    label: '总结 / 报告',
    icon: ScrollText
  }
]
const title = computed(() => String(route.meta.title || '工作台'))

// 后端服务可达性：定时探测真实业务端点；请求失败/非 2xx 即视为服务不可用
const backendOnline = ref(true)
const HEALTH_URL = '/api/documents?page=0&size=1'
const POLL_INTERVAL_MS = 10000
let healthTimer: number | null = null

async function checkHealth() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 5000)
  try {
    const resp = await fetch(HEALTH_URL, {signal: controller.signal, headers: {Accept: 'application/json'}})
    backendOnline.value = resp.ok
  } catch {
    backendOnline.value = false
  } finally {
    clearTimeout(timer)
  }
}

function handleOffline() {
  backendOnline.value = false
}

function handleOnline() {
  checkHealth()
}

onMounted(() => {
  checkHealth()
  healthTimer = window.setInterval(checkHealth, POLL_INTERVAL_MS)
  window.addEventListener('offline', handleOffline)
  window.addEventListener('online', handleOnline)
})

onUnmounted(() => {
  if (healthTimer !== null) window.clearInterval(healthTimer)
  window.removeEventListener('offline', handleOffline)
  window.removeEventListener('online', handleOnline)
})
</script>
<template>
  <div class="app-shell">
    <aside :class="{ 'is-open': open }" class="sidebar">
      <div class="brand"><span class="brand-mark"><Bot :size="18"/></span><span>Roch</span><small>RAG 工作台</small>
        <button class="icon-button sidebar-close" @click="open = false">
          <X :size="18"/>
        </button>
      </div>
      <nav>
        <RouterLink v-for="item in items" :key="item.to" :to="item.to" @click="open = false">
          <component :is="item.icon" :size="18"/>
          <span>{{ item.label }}</span></RouterLink>
      </nav>
      <div class="sidebar-foot">
        <PanelLeftClose :size="16"/>
        <span>本地知识工作台</span></div>
    </aside>
    <div class="main-column">
      <header class="topbar">
        <button class="icon-button menu-button" @click="open = !open">
          <Menu :size="20"/>
        </button>
        <div><p class="eyebrow">ROCH / WORKSPACE</p>
          <h1>{{ title }}</h1></div>
        <div :class="['topbar-status', {'is-offline': !backendOnline}]">
          <span class="status-dot"></span>
          {{ backendOnline ? '服务在线' : '服务不可用' }}
        </div>
      </header>
      <main class="content">
        <RouterView/>
      </main>
    </div>
  </div>
</template>

<style scoped>
.topbar-status.is-offline {
  color: #c0392b;
  font-weight: 600;
}

.topbar-status.is-offline .status-dot {
  background: #e05b5b;
}

/* 移动端全局样式默认隐藏状态区，服务不可用时仍需提示，优先级覆盖 display:none */
@media (max-width: 600px) {
  .topbar-status.is-offline {
    display: flex;
  }
}
</style>
