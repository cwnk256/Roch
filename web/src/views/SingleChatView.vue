<script lang="ts" setup>
import {ref} from 'vue';
import {Send, Sparkles} from 'lucide-vue-next';
import {api} from '../api';

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
  <section class="page-intro">
    <div><p class="eyebrow">CHAT / SINGLE TURN</p>
      <h2>单轮问答</h2>
      <p>一次提问，一次回答，适合快速搜确认知识点。</p></div>
  </section>
  <div class="chat-layout">
    <div class="panel chat-panel">
      <div class="messages">
        <div v-if="!reply&&!loading" class="empty-state">
          <Sparkles :size="24"/>
          <strong>准备好回答你的问题</strong><span>试试询问文档中的一个具体事实。</span></div>
        <div v-if="reply" class="message assistant"><span class="message-label">ROCH</span>
          <div class="message-bubble">{{ reply }}</div>
        </div>
        <div v-if="loading" class="message assistant"><span class="message-label">ROCH · 思考中</span>
          <div class="message-bubble">正在检索并生成回答…</div>
        </div>
      </div>
      <div v-if="error" class="notice error">{{ error }}</div>
      <form class="chat-compose" @submit.prevent="send"><textarea v-model="input" placeholder="输入你的问题…"
                                                                  @keydown.enter.exact.prevent="send"></textarea>
        <button class="button primary" title="发送" type="submit">
          <Send :size="16"/>
        </button>
      </form>
    </div>
    <div class="panel" style="padding:18px">
      <div class="section-heading"><h3>使用提示</h3></div>
      <p class="muted" style="font-size:13px;line-height:1.7">
        单轮问答不会保留上下文，适合独立问题。需要连续追问时，请切换到多轮问答。</p></div>
  </div>
</template>
