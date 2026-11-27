<script setup lang="ts">
import {ref} from 'vue'

defineProps<{
  page: number
  preview: {url: string; highlight: {left: string; top: string; width: string; height: string}}
}>()
const opened = ref(false)
const status = ref<'loading' | 'loaded' | 'error'>('loading')

function openPreview(event: Event) {
  if ((event.currentTarget as HTMLDetailsElement).open) opened.value = true
}
</script>

<template>
  <details class="ocr-preview" @toggle="openPreview">
    <summary>查看第 {{ page }} 页定位</summary>
    <div v-if="opened" class="ocr-preview-content">
      <p v-if="status === 'error'" role="status">无法预览该页，请打开原 PDF 核对。</p>
      <template v-else>
        <p v-if="status === 'loading'" role="status">正在加载扫描原页…</p>
        <figure v-show="status === 'loaded'">
          <div class="ocr-page">
            <img :src="preview.url" :alt="`原 PDF 第 ${page} 页`" @load="status = 'loaded'" @error="status = 'error'"/>
            <div class="ocr-highlight" :style="preview.highlight" aria-hidden="true"></div>
          </div>
          <figcaption>框内为引文在原页的位置</figcaption>
        </figure>
      </template>
    </div>
  </details>
</template>

<style scoped>
.ocr-preview { margin: 10px 0 8px }
.ocr-preview > summary { color: #1c7457; cursor: pointer; width: fit-content; font-size: 12px }
.ocr-preview > summary:focus-visible { outline: 2px solid #1c7457; outline-offset: 3px }
.ocr-preview-content { margin-top: 8px; max-width: 720px }
.ocr-preview-content p { margin: 0; color: #536c61; font-size: 12px }
figure { margin: 0 }
.ocr-page { position: relative; box-shadow: 0 0 0 1px #dfe9e3 }
img { display: block; width: 100%; height: auto }
.ocr-highlight { position: absolute; pointer-events: none; background: rgb(255 193 69 / .24); outline: 2px solid #996b13; outline-offset: -1px }
figcaption { margin-top: 5px; color: #536c61; font-size: 12px }
</style>
