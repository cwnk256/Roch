<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {http} from '../api/http'
import {desktopBridge, type CredentialStatus} from '../lib/desktop-bridge'

interface Connection {
  modelOrigin: string
  embeddingModel: string
  embeddingDimension: number
}
interface Settings extends Connection {
  model: string
  temperature: number | null
  topP: number | null
  maxTokens: number | null
  outputDirectory: string
}
interface State {
  settings: Settings
  activeConnection: Connection
  generationSettings: Settings
  restartRequired: boolean
  embeddingLocked: boolean
}
interface Model {
  id: string
  name: string
  type: 'llm' | 'embedding'
  loaded: boolean
}

const bridge = desktopBridge()!
const state = ref<State>()
const draft = ref<Settings>()
const models = ref<Model[]>([])
const busy = ref(false)
const status = ref('')
const error = ref('')
const credentialRestart = ref(false)
const credentialStatus = ref<CredentialStatus>()
const keys = ref({firecrawl: '', amapGeo: '', amapWeather: ''})
const credentials = [
  {key: 'firecrawl', label: 'Firecrawl'},
  {key: 'amapGeo', label: '高德地理编码'},
  {key: 'amapWeather', label: '高德天气'}
] as const

function applyState(value: State) {
  state.value = value
  draft.value = {...value.settings}
}

async function perform(operation: () => Promise<void>) {
  if (busy.value) return
  error.value = ''
  status.value = ''
  busy.value = true
  try {
    await operation()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : String(reason)
  } finally {
    busy.value = false
  }
}

const load = () => perform(async () => {
  const {data} = await http.get<State>('/desktop/settings')
  applyState(data)
  credentialStatus.value = await bridge.credentialStatus()
})

const refreshModels = () => perform(async () => {
  models.value = []
  const {data} = await http.post<{models: Model[]}>('/desktop/settings/models', {modelOrigin: draft.value?.modelOrigin})
  models.value = data.models
  status.value = data.models.length ? '已刷新模型列表。' : '请先在 LM Studio 下载模型。'
})

const testModel = (type: Model['type']) => perform(async () => {
  const settings = draft.value!
  const {data} = await http.post<{message: string; dimension: number | null}>('/desktop/settings/test-model', {
    modelOrigin: settings.modelOrigin,
    model: type === 'llm' ? settings.model : settings.embeddingModel,
    type
  })
  if (data.dimension != null && !state.value?.embeddingLocked) settings.embeddingDimension = data.dimension
  status.value = data.dimension == null ? data.message : data.message + ' 向量维度 ' + data.dimension + '。'
})

const chooseDirectory = () => perform(async () => {
  const directory = await bridge.chooseDirectory()
  if (directory && draft.value) draft.value.outputDirectory = directory
})

function optionalNumber(value: number | null | '') {
  return value === '' ? null : value
}

const save = () => perform(async () => {
  const settings = draft.value!
  const {data} = await http.put<State>('/desktop/settings', {
    ...settings, temperature: optionalNumber(settings.temperature),
    topP: optionalNumber(settings.topP), maxTokens: optionalNumber(settings.maxTokens)
  })
  applyState(data)
  status.value = data.restartRequired ? '已保存。重启后应用新的嵌入连接。' : '设置已保存。'
})

const updateCredential = (key: keyof CredentialStatus, value: string) => perform(async () => {
  await bridge.saveCredentials({[key]: value})
  keys.value[key] = ''
  credentialRestart.value = true
  credentialStatus.value = await bridge.credentialStatus()
  status.value = '密钥设置已保存。重启后生效。'
})

onMounted(load)
</script>

<template>
  <div class="settings">
    <p v-if="error" role="alert" class="message error">{{ error }}</p>
    <p v-if="status" role="status" class="message">{{ status }}</p>
    <button v-if="!draft" class="button" :disabled="busy" @click="load">{{ busy ? '正在读取…' : '重新读取设置' }}</button>
    <form v-else class="settings" @submit.prevent="save">
      <div v-if="state?.restartRequired || credentialRestart" class="message row">
        <span>有设置将在重启后生效。</span>
        <button class="button primary" type="button" :disabled="busy" @click="perform(() => bridge.restart())">重启 Roch</button>
      </div>
      <fieldset :disabled="busy">
        <legend>模型连接</legend>
        <p>当前连接 {{ state?.activeConnection.modelOrigin }} · {{ state?.activeConnection.embeddingModel }} · {{ state?.activeConnection.embeddingDimension }} 维</p>
        <p v-if="state?.embeddingLocked">知识库已有索引，嵌入连接已锁定。</p>
        <div class="row">
          <label>LM Studio 地址 <input v-model.trim="draft.modelOrigin" :readonly="state?.embeddingLocked" required @input="models = []"></label>
          <button class="button" type="button" @click="refreshModels">刷新模型列表</button>
        </div>
        <div class="row">
          <label>嵌入模型
            <input v-model.trim="draft.embeddingModel" :readonly="state?.embeddingLocked" list="embedding-models" required>
            <datalist id="embedding-models">
              <option v-for="model in models.filter(item => item.type === 'embedding')" :key="model.id" :value="model.id"
                      :label="model.name + (model.loaded ? ' · 已加载' : ' · 未加载')"/>
            </datalist>
          </label>
          <label>向量维度 <input :value="draft.embeddingDimension" readonly></label>
          <button class="button" type="button" @click="testModel('embedding')">测试嵌入</button>
        </div>
      </fieldset>
      <fieldset :disabled="busy">
        <legend>生成与问答</legend>
        <p>当前生成模型 {{ state?.generationSettings.model || '未配置' }}。参数留空时使用 LM Studio 默认值。</p>
        <div class="row">
          <label>生成模型
            <input v-model.trim="draft.model" list="generate-models" required>
            <datalist id="generate-models">
              <option v-for="model in models.filter(item => item.type === 'llm')" :key="model.id" :value="model.id"
                      :label="model.name + (model.loaded ? ' · 已加载' : ' · 未加载')"/>
            </datalist>
          </label>
          <button class="button" type="button" @click="testModel('llm')">测试结构化生成</button>
        </div>
        <div class="row">
          <label>温度 <input v-model.number="draft.temperature" type="number" min="0" max="2" step="any" placeholder="服务默认"></label>
          <label>Top P <input v-model.number="draft.topP" type="number" min="0" max="1" step="any" placeholder="服务默认"></label>
          <label>最大输出 token 数 <input v-model.number="draft.maxTokens" type="number" min="1" max="131072" placeholder="服务默认"></label>
        </div>
      </fieldset>
      <fieldset :disabled="busy">
        <legend>报告输出</legend>
        <div class="row">
          <label>输出目录 <input :value="draft.outputDirectory" readonly></label>
          <button class="button" type="button" @click="chooseDirectory">选择目录</button>
        </div>
      </fieldset>
      <div><button class="button primary" :disabled="busy">保存设置</button></div>
    </form>
    <fieldset v-if="credentialStatus" :disabled="busy">
      <legend>外部服务密钥</legend>
      <div v-for="{key, label} in credentials" :key="key" class="row">
        <label>{{ label }} · {{ credentialStatus[key] ? '已配置' : '未配置' }}
          <input v-model.trim="keys[key]" type="password" autocomplete="off" spellcheck="false" placeholder="输入新密钥">
        </label>
        <button class="button" :disabled="!keys[key]" @click="updateCredential(key, keys[key])">保存密钥</button>
        <button class="button" :disabled="!credentialStatus[key]" @click="updateCredential(key, '')">清除</button>
      </div>
    </fieldset>
  </div>
</template>

<style scoped>
.settings { display: grid; gap: 20px; max-width: 850px }
fieldset { display: grid; gap: 14px; min-width: 0; margin: 0; padding: 14px 20px 19px; border: 1px solid #dfe9e3; border-radius: 10px; background: white }
legend { padding: 0 6px; color: #254f40; font-weight: 650 }
label { display: grid; min-width: 0; gap: 6px; color: #365e4e; font-size: 14px }
input { width: 100%; min-width: 0 }
input[readonly] { background: #f6f9f7 }
.row { display: flex; align-items: end; flex-wrap: wrap; gap: 10px }
.row label { flex: 1 1 170px }
fieldset p { margin: 0; color: #506a5f; font-size: 13px; overflow-wrap: anywhere }
.message { margin: 0; padding: 12px 14px; background: #eaf4ee; color: #254f40; border-radius: 7px }
.message.error { background: #fbecea; color: #8f2e29 }
.message.row { align-items: center; justify-content: space-between }
</style>
