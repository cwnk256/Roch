<script lang="ts" setup>
import {ref} from 'vue';
import {Sparkles} from 'lucide-vue-next';
import {RouterLink} from 'vue-router';
import {api} from '../api';
import ChatComposer from '../components/ChatComposer.vue';

const input = ref(''), reply = ref(''), loading = ref(false), error = ref('');

async function send() {
  if (!input.value.trim() || loading.value) return;
  const q = input.value;
  input.value = '';
  loading.value = true;
  error.value = '';
  try {
    reply.value = (await api.chat(q)).reply
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <div class="page-intro"><p>向知识库提一个独立问题，快速核对文档中的事实。</p></div>
  <div class="chat-layout">
    <div class="panel chat-panel">
      <div class="messages" tabindex="0" role="region" aria-label="问答结果">
        <div v-if="!reply&&!loading" class="empty-state">
          <Sparkles :size="24" aria-hidden="true"/>
          <strong>准备好回答你的问题</strong><span>试试询问文档中的一个具体事实。</span></div>
        <div v-if="reply" class="message assistant"><span class="message-label">Roch</span>
          <div class="message-bubble">{{ reply }}</div>
        </div>
        <div v-if="loading" class="message assistant" role="status"><span class="message-label">Roch</span>
          <div class="message-bubble">正在检索并生成回答…</div>
        </div>
      </div>
      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
      <ChatComposer v-model="input" :loading="loading" placeholder="输入你的问题…" @send="send"/>
    </div>
    <aside class="panel panel-pad chat-tips">
      <div class="section-heading"><h2>需要继续追问？</h2></div>
      <p class="muted">多轮问答会保留当前会话的上下文。</p>
      <RouterLink to="/conversation">打开多轮问答</RouterLink>
    </aside>
  </div>
</template>

<style scoped>
.chat-tips p { font-size: var(--font-small); line-height: 1.65 }
.chat-tips a { font-size: var(--font-small) }
</style>
