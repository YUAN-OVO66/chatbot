<script setup lang="ts">
import { Welcome, Prompts } from 'vue-element-plus-x'
import type { PromptsItemsProps } from 'vue-element-plus-x/types/Prompts'

const emit = defineEmits<{
  send: [content: string]
}>()

const promptItems: PromptsItemsProps[] = [
  { key: '1', label: '帮我写一段代码', description: '描述你想要的功能' },
  { key: '2', label: '解释一个概念', description: '输入你想了解的概念' },
  { key: '3', label: '翻译文本', description: '支持多语言翻译' },
  { key: '4', label: '总结内容', description: '粘贴需要总结的文本' },
]

function handlePromptClick(item: PromptsItemsProps) {
  if (item.label) {
    emit('send', item.label)
  }
}
</script>

<template>
  <div class="welcome-panel">
    <Welcome
      icon=""
      title="AI 智能助手"
      description="我可以帮助你解答问题、编写代码、翻译文本、总结内容等。有什么我可以帮你的吗？"
      variant="borderless"
    />

    <Prompts
      :items="promptItems"
      @item-click="handlePromptClick"
    />
  </div>
</template>

<style scoped>
.welcome-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 40px 20px;
  gap: 40px;
  background: linear-gradient(180deg, var(--color-bg) 0%, var(--color-surface) 100%);
}
</style>
