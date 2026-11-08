<script lang="ts" setup>
import {computed, nextTick, onMounted, ref} from 'vue'
import {Clock3, MessageSquare, MessageSquarePlus, RefreshCw, Send, Trash2, X} from 'lucide-vue-next'
import {api, type Conversation} from '../api'
import {scrollMessagesToBottom} from '../lib/chat-scroll'

type Message = { role: string; content: string }
const conversationId = ref('')
const conversations = ref<Conversation[]>([])
const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)
const loadingHistory = ref(false)
const error = ref('')
const messagePane = ref<HTMLElement | null>(null)
const confirmDeleteId = ref<string | null>(null)
const currentConversation = computed(() => conversations.value.find(item => item.conversationId === conversationId.value))

async function scrollToLatestMessage() {
  await nextTick();
  scrollMessagesToBottom(messagePane.value)
}

async function refreshConversations() {
  conversations.value = await api.listConversations()
}

async function selectConversation(id: string) {
  if (loadingHistory.value || id === conversationId.value && messages.value.length) return
  loadingHistory.value = true;
  error.value = '';
  conversationId.value = id
  try {
    messages.value = (await api.history(id)).messages || [];
    await scrollToLatestMessage()
  } catch (e) {
    error.value = String(e);
    messages.value = []
  } finally {
    loadingHistory.value = false
  }
}

async function newChat() {
  error.value = '';
  const created = await api.createConversation()
  conversations.value = [created, ...conversations.value.filter(item => item.conversationId !== created.conversationId)]
  conversationId.value = created.conversationId;
  messages.value = [];
  await scrollToLatestMessage()
}

async function load() {
  try {
    await refreshConversations();
    if (conversations.value.length) await selectConversation(conversations.value[0].conversationId); else await newChat()
  } catch (e) {
    error.value = String(e)
  }
}

async function send() {
  const text = input.value.trim();
  if (!text || loading.value) return
  error.value = '';
  // 不预建会话：conversationId 为空时由后端创建，通过 conversation 事件回填，避免产生空会话
  const id = conversationId.value || undefined;
  messages.value.push({role: 'USER', content: text});
  messages.value.push({role: 'ASSISTANT', content: ''});
  const assistantIndex = messages.value.length - 1;
  input.value = '';
  loading.value = true;
  await scrollToLatestMessage();
  let streamError = '';
  try {
    await api.multiTurnStream({
      conversationId: id,
      message: text,
      ragEnabled: true
    }, {
      onConversation: (newId) => {
        if (!conversationId.value) conversationId.value = newId;
      },
      onContent: (delta) => {
        const msg = messages.value[assistantIndex];
        if (msg) msg.content += delta;
        void scrollToLatestMessage();
      },
      onErrorEvent: (message) => {
        streamError = message;
      }
    });
    if (streamError) throw new Error(streamError);
    await refreshConversations()
  } catch (e) {
    error.value = String(e);
    // 一个字都没收到时移除空气泡，避免界面残留空白回复
    if (!messages.value[assistantIndex]?.content) messages.value.splice(assistantIndex, 1);
  } finally {
    loading.value = false
  }
}

async function clearConversation() {
  if (!conversationId.value) return;
  try {
    await api.clearConversation(conversationId.value);
    messages.value = [];
    await scrollToLatestMessage()
  } catch (e) {
    error.value = String(e)
  }
}

async function deleteConversation(id: string) {
  try {
    await api.deleteConversation(id);
    conversations.value = conversations.value.filter(item => item.conversationId !== id);
    if (id === conversationId.value) {
      if (conversations.value.length) {
        await selectConversation(conversations.value[0].conversationId)
      } else {
        await newChat()
      }
    }
  } catch (e) {
    error.value = String(e)
  } finally {
    confirmDeleteId.value = null
  }
}

function titleOf(item: Conversation) {
  return item.title || '新会话'
}

function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

onMounted(load)
</script>

<template>
  <section class="page-intro">
    <div><p class="eyebrow">CHAT / MEMORY</p>
      <h2>多轮问答</h2>
      <p>保留当前会话上下文，支持从历史会话继续追问，适合深度求索知识。</p></div>
    <div class="actions">
      <button class="button" type="button" @click="newChat">
        <MessageSquarePlus :size="15"/>
        新会话
      </button>
      <button aria-label="刷新会话列表" class="icon-button" title="刷新会话列表" type="button" @click="load">
        <RefreshCw :size="16"/>
      </button>
    </div>
  </section>
  <div class="chat-workspace panel">
    <aside aria-label="历史会话" class="conversation-sidebar">
      <div class="conversation-sidebar-header">
        <div><span class="eyebrow">HISTORY</span>
          <h3>历史对话</h3></div>
        <button aria-label="新建会话" class="icon-button" title="新建会话" type="button" @click="newChat">
          <MessageSquarePlus :size="17"/>
        </button>
      </div>
      <div v-if="!conversations.length" class="conversation-empty">暂无历史会话</div>
      <nav v-else class="conversation-list">
        <div v-for="item in conversations" :key="item.conversationId"
             :class="{ active: item.conversationId === conversationId }" class="conversation-item">
          <button class="conversation-item-main" type="button"
                  @click="selectConversation(item.conversationId)">
            <MessageSquare :size="15"/>
            <span class="conversation-item-copy"><strong>{{ titleOf(item) }}</strong><small><Clock3
                :size="11"/>{{ formatTime(item.updatedAt) }}</small></span>
          </button>
          <button v-if="confirmDeleteId === item.conversationId" aria-label="确认删除" class="icon-button danger"
                  title="确认删除" type="button"
                  @click.stop="deleteConversation(item.conversationId)">
            <Trash2 :size="15"/>
          </button>
          <button v-else aria-label="删除会话" class="icon-button conversation-item-delete" title="删除会话"
                  type="button"
                  @click.stop="confirmDeleteId = item.conversationId">
            <X :size="15"/>
          </button>
        </div>
      </nav>
    </aside>
    <section class="chat-panel">
      <header class="chat-panel-header">
        <div><span class="eyebrow">CURRENT CONVERSATION</span>
          <h3>{{ currentConversation ? titleOf(currentConversation) : '新会话' }}</h3></div>
        <button :disabled="!messages.length" class="button danger" type="button" @click="clearConversation">
          <Trash2 :size="15"/>
          清空记录
        </button>
      </header>
      <div ref="messagePane" aria-label="消息记录" class="messages" tabindex="0">
        <div v-if="loadingHistory" class="empty-state"><strong>正在加载历史记录</strong></div>
        <div v-else-if="!messages.length" class="empty-state"><strong>会话已准备好</strong><span>试试询问文档中的一个具体事实。</span>
        </div>
        <template v-else>
          <div v-for="(message, index) in messages" :key="index"
               :class="{ user: message.role === 'USER' || message.role === 'user' }" class="message"><span
              class="message-label">{{ message.role === 'USER' || message.role === 'user' ? '你' : 'ROCH' }}</span>
            <div class="message-bubble">
              <template v-if="message.content">{{ message.content }}</template>
              <span v-else-if="loading && index === messages.length - 1" class="typing-placeholder">正在思考…</span>
            </div>
          </div>
        </template>
      </div>
      <div v-if="error" class="notice error">{{ error }}</div>
      <form class="chat-compose" @submit.prevent="send"><textarea v-model="input" aria-label="消息内容"
                                                                  placeholder="继续追问…"
                                                                  @keydown.enter.exact.prevent="send"/>
        <button :disabled="loading" class="button primary" type="submit">
          <Send :size="16"/>
          发送
        </button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.conversation-item-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  text-align: left;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 0;
}

.conversation-item-delete {
  flex: none;
  opacity: 0;
  transition: opacity .15s;
  color: #999;
}

.conversation-item:hover .conversation-item-delete {
  opacity: 1;
}

.conversation-item-delete:hover {
  color: #c0392b;
}

.typing-placeholder {
  color: #9aa0a6;
  animation: typing-blink 1.2s ease-in-out infinite;
}

@keyframes typing-blink {
  0%, 100% { opacity: .35; }
  50% { opacity: 1; }
}
</style>
