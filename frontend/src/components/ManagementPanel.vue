<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import { getDocuments, uploadDocument, deleteDocument } from '@/api/rag'
import { getPlugins, enablePlugin, disablePlugin } from '@/api/plugin'
import { getSkills } from '@/api/skill'
import { getMemoryFacts, deleteMemoryFact, getPreferences } from '@/api/memory'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { RagDocument, Plugin, Skill, MemoryFact, Preference } from '@/types'

const userStore = useUserStore()
const activeTab = ref('rag')

// RAG state
const documents = ref<RagDocument[]>([])
const uploading = ref(false)

// Plugin state
const plugins = ref<Plugin[]>([])

// Skill state
const skills = ref<Skill[]>([])

// Memory state
const facts = ref<MemoryFact[]>([])
const preferences = ref<Preference[]>([])

// Load data based on active tab
async function loadData() {
  if (!userStore.userId) return
  try {
    if (activeTab.value === 'rag') {
      const res = await getDocuments(userStore.userId)
      documents.value = res.data
    } else if (activeTab.value === 'plugins') {
      const res = await getPlugins()
      plugins.value = res.data
    } else if (activeTab.value === 'skills') {
      const res = await getSkills()
      skills.value = res.data
    } else if (activeTab.value === 'memory') {
      const [factsRes, prefsRes] = await Promise.all([
        getMemoryFacts(userStore.userId),
        getPreferences(userStore.userId),
      ])
      facts.value = factsRes.data
      preferences.value = prefsRes.data
    }
  } catch {
    // Error handled by request interceptor
  }
}

// RAG functions
async function handleUpload(file: File) {
  if (!userStore.userId) return
  uploading.value = true
  try {
    await uploadDocument(userStore.userId, file)
    ElMessage.success('上传成功')
    await loadData()
  } catch {
    ElMessage.error('上传失败')
  } finally {
    uploading.value = false
  }
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files?.[0]) {
    handleUpload(input.files[0])
    input.value = ''
  }
}

async function handleDeleteDoc(doc: RagDocument) {
  await ElMessageBox.confirm(`确定删除文档 "${doc.fileName}"？`, '确认')
  await deleteDocument(doc.id, userStore.userId!)
  ElMessage.success('已删除')
  await loadData()
}

// Plugin functions
async function togglePlugin(plugin: Plugin) {
  if (plugin.enabled) {
    await disablePlugin(plugin.name)
  } else {
    await enablePlugin(plugin.name)
  }
  plugin.enabled = !plugin.enabled
  ElMessage.success(`${plugin.name} 已${plugin.enabled ? '启用' : '禁用'}`)
}

// Memory functions
async function handleDeleteFact(fact: MemoryFact) {
  await ElMessageBox.confirm('确定删除这条记忆？', '确认')
  await deleteMemoryFact(fact.id)
  ElMessage.success('已删除')
  await loadData()
}

onMounted(loadData)
</script>

<template>
  <div class="management-panel">
    <el-tabs v-model="activeTab" @tab-change="loadData">
      <!-- RAG 知识库 -->
      <el-tab-pane label="知识库" name="rag">
        <div class="tab-header">
          <el-button type="primary" :loading="uploading" @click="($refs.fileInput as HTMLInputElement).click()">
            上传文档
          </el-button>
          <input
            ref="fileInput"
            type="file"
            accept=".pdf,.txt,.md"
            style="display: none"
            @change="handleFileChange"
          />
          <span class="hint">支持 PDF / TXT / MD 格式</span>
        </div>
        <el-table :data="documents" stripe>
          <el-table-column prop="fileName" label="文件名" />
          <el-table-column prop="fileType" label="类型" width="80" />
          <el-table-column prop="chunkCount" label="分块数" width="80" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="danger" @click="handleDeleteDoc(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 插件管理 -->
      <el-tab-pane label="插件" name="plugins">
        <el-table :data="plugins" stripe>
          <el-table-column prop="name" label="插件名称" />
          <el-table-column prop="order" label="优先级" width="100" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-switch :model-value="row.enabled" @change="togglePlugin(row)" />
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 技能管理 -->
      <el-tab-pane label="技能" name="skills">
        <el-table :data="skills" stripe>
          <el-table-column prop="name" label="技能名称" width="150" />
          <el-table-column prop="description" label="描述" />
        </el-table>
      </el-tab-pane>

      <!-- 记忆管理 -->
      <el-tab-pane label="记忆" name="memory">
        <h4>记忆事实</h4>
        <el-table :data="facts" stripe>
          <el-table-column prop="factText" label="内容" />
          <el-table-column prop="category" label="分类" width="100" />
          <el-table-column prop="importance" label="重要性" width="80" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="danger" @click="handleDeleteFact(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4 style="margin-top: 20px;">用户偏好</h4>
        <el-table :data="preferences" stripe>
          <el-table-column prop="preferenceKey" label="键" width="150" />
          <el-table-column prop="preferenceValue" label="值" />
          <el-table-column prop="confidence" label="置信度" width="100" />
          <el-table-column prop="source" label="来源" width="100" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.management-panel {
  padding: 16px;
}

.tab-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.hint {
  color: #909399;
  font-size: 13px;
}
</style>
