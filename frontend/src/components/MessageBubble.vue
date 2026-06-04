<script setup lang="ts">
import { Bubble } from 'vue-element-plus-x'
import { MarkdownRenderer } from 'x-markdown-vue'
import 'x-markdown-vue/style'

defineProps<{
  role: 'user' | 'assistant'
  content: string
  loading?: boolean
}>()
</script>

<template>
  <Bubble
    :placement="role === 'user' ? 'end' : 'start'"
    :content="content"
    :loading="loading"
    :max-width="'700px'"
    :variant="role === 'user' ? 'filled' : 'borderless'"
  >
    <template v-if="role === 'assistant'" #content>
      <div class="markdown-body">
        <MarkdownRenderer :markdown="content" />
      </div>
    </template>
  </Bubble>
</template>

<style scoped>
.markdown-body {
  line-height: 1.6;
  font-size: 14px;
}

.markdown-body :deep(pre) {
  background: #f6f8fa;
  border-radius: 6px;
  padding: 12px;
  overflow-x: auto;
}

.markdown-body :deep(code) {
  font-family: 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 13px;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 20px;
}
</style>
