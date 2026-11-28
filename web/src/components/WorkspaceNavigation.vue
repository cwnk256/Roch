<script lang="ts" setup>
import {RouterLink} from 'vue-router'
import {BarChart3, Files, FileSearch, FileUp, Globe, MessageCircle, MessagesSquare, ScrollText} from 'lucide-vue-next'

defineEmits<{ select: [path: string] }>()

const navigationGroups = [
  {
    label: '工作台',
    items: [{to: '/dashboard', label: '仪表盘', icon: BarChart3}]
  },
  {
    label: '知识库',
    items: [
      {to: '/search', label: '文件检索', icon: FileSearch},
      {to: '/documents', label: '文档管理', icon: Files},
      {to: '/import', label: '导入文档', icon: FileUp},
      {to: '/webpage', label: '获取外部网页', icon: Globe}
    ]
  },
  {
    label: '问答与报告',
    items: [
      {to: '/chat', label: '单轮问答', icon: MessageCircle},
      {to: '/conversation', label: '多轮问答', icon: MessagesSquare},
      {to: '/reports', label: '总结 / 报告', icon: ScrollText}
    ]
  }
]
</script>

<template>
  <nav aria-label="工作台导航" class="workspace-nav">
    <div v-for="group in navigationGroups" :key="group.label" class="nav-group">
      <p class="nav-group-label">{{ group.label }}</p>
      <RouterLink v-for="item in group.items" :key="item.to" :to="item.to" @click="$emit('select', item.to)">
        <component :is="item.icon" :size="17" aria-hidden="true"/>
        <span>{{ item.label }}</span>
      </RouterLink>
    </div>
  </nav>
</template>
