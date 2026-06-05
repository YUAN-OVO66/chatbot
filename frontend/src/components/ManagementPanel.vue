<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { getDocuments, uploadDocument, deleteDocument } from '@/api/rag'
import { getPlugins, enablePlugin, disablePlugin } from '@/api/plugin'
import { getSkills } from '@/api/skill'
import {
  getMemoryFacts,
  deleteMemoryFact,
  createMemoryFact,
  updateMemoryFact,
  getPreferences,
  setPreference,
  deletePreference,
  getMemoryStats,
  consolidateMemory,
  extractMemory,
} from '@/api/memory'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  RagDocument, Plugin, Skill, MemoryFact, Preference, MemoryStats,
  FactCreateRequest, FactUpdateRequest, SetPreferenceRequest,
} from '@/types'

const userStore = useUserStore()
const chatStore = useChatStore()
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
const stats = ref<MemoryStats>({ totalFacts: 0, totalPreferences: 0 })
const factCategory = ref<string>('')
const consolidating = ref(false)
const extracting = ref(false)

// Fact dialog state
const factDialogVisible = ref(false)
const factDialogMode = ref<'create' | 'edit'>('create')
const editingFactId = ref<number | null>(null)
const factForm = ref<FactCreateRequest>({
  userId: '',
  factText: '',
  category: 'general',
  importance: 5,
})

// Preference dialog state
const prefDialogVisible = ref(false)
const prefForm = ref<SetPreferenceRequest>({
  userId: '',
  preferenceKey: '',
  preferenceValue: '',
})

const categoryOptions = [
  { label: '全部', value: '' },
  { label: '个人信息', value: 'personal_info' },
  { label: '工作', value: 'work' },
  { label: '习惯', value: 'habit' },
  { label: '通用', value: 'general' },
]

const categoryLabelMap: Record<string, string> = {
  personal_info: '个人信息',
  work: '工作',
  habit: '习惯',
  general: '通用',
}

const sourceLabelMap: Record<string, string> = {
  explicit: '手动',
  extracted: '自动',
}

const currentSessionId = computed(() => chatStore.currentSessionId)

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
      await loadMemoryData()
    }
  } catch {
    // Error handled by request interceptor
  }
}

async function loadMemoryData() {
  if (!userStore.userId) return
  const [factsRes, prefsRes, statsRes] = await Promise.all([
    getMemoryFacts(userStore.userId, factCategory.value || undefined),
    getPreferences(userStore.userId),
    getMemoryStats(userStore.userId),
  ])
  facts.value = factsRes.data
  preferences.value = prefsRes.data
  stats.value = statsRes.data
}

watch(factCategory, () => {
  if (activeTab.value === 'memory') loadMemoryData()
})

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

// Memory - Fact functions
function openCreateFactDialog() {
  factDialogMode.value = 'create'
  editingFactId.value = null
  factForm.value = {
    userId: userStore.userId!,
    factText: '',
    category: 'general',
    importance: 5,
  }
  factDialogVisible.value = true
}

function openEditFactDialog(fact: MemoryFact) {
  factDialogMode.value = 'edit'
  editingFactId.value = fact.id
  factForm.value = {
    userId: userStore.userId!,
    factText: fact.factText,
    category: fact.category,
    importance: fact.importance,
  }
  factDialogVisible.value = true
}

async function submitFact() {
  if (!factForm.value.factText.trim()) {
    ElMessage.warning('请输入事实内容')
    return
  }
  if (factDialogMode.value === 'create') {
    await createMemoryFact(factForm.value)
    ElMessage.success('事实已创建')
  } else {
    const updateParams: FactUpdateRequest = {
      userId: factForm.value.userId,
      factText: factForm.value.factText,
      category: factForm.value.category,
      importance: factForm.value.importance,
    }
    await updateMemoryFact(editingFactId.value!, updateParams)
    ElMessage.success('事实已更新')
  }
  factDialogVisible.value = false
  await loadMemoryData()
}

async function handleDeleteFact(fact: MemoryFact) {
  await ElMessageBox.confirm('确定删除这条记忆？', '确认')
  await deleteMemoryFact(fact.id)
  ElMessage.success('已删除')
  await loadMemoryData()
}

// Memory - Preference functions
function openCreatePrefDialog() {
  prefForm.value = {
    userId: userStore.userId!,
    preferenceKey: '',
    preferenceValue: '',
  }
  prefDialogVisible.value = true
}

async function submitPreference() {
  if (!prefForm.value.preferenceKey.trim() || !prefForm.value.preferenceValue.trim()) {
    ElMessage.warning('请填写完整的偏好信息')
    return
  }
  await setPreference(prefForm.value)
  ElMessage.success('偏好已保存')
  prefDialogVisible.value = false
  await loadMemoryData()
}

async function handleDeletePreference(pref: Preference) {
  await ElMessageBox.confirm(`确定删除偏好 "${pref.preferenceKey}"？`, '确认')
  await deletePreference(pref.preferenceKey, userStore.userId!)
  ElMessage.success('已删除')
  await loadMemoryData()
}

// Memory - Consolidate & Extract
async function handleConsolidate() {
  consolidating.value = true
  try {
    await consolidateMemory(userStore.userId!)
    ElMessage.success('记忆整合完成')
    await loadMemoryData()
  } catch {
    // handled by interceptor
  } finally {
    consolidating.value = false
  }
}

async function handleExtract() {
  if (!currentSessionId.value) {
    ElMessage.warning('请先选择一个会话')
    return
  }
  extracting.value = true
  try {
    await extractMemory(currentSessionId.value, userStore.userId!)
    ElMessage.success('记忆提取完成')
    await loadMemoryData()
  } catch {
    // handled by interceptor
  } finally {
    extracting.value = false
  }
}

async function copyUserId() {
  if (userStore.userId) {
    await navigator.clipboard.writeText(userStore.userId)
    ElMessage.success('已复制用户 ID')
  }
}
</script>

<template>
  <div class="management-panel">
    <div class="user-info">
      <span class="user-label">用户 ID</span>
      <el-input
        :model-value="userStore.userId || ''"
        readonly
        size="small"
        class="user-id-input"
      >
        <template #append>
          <el-button @click="copyUserId">
            复制
          </el-button>
        </template>
      </el-input>
    </div>

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
        <!-- 统计信息 -->
        <div class="memory-stats">
          <span class="stats-text">{{ stats.totalFacts }} 条事实 · {{ stats.totalPreferences }} 条偏好</span>
          <div class="stats-actions">
            <el-button size="small" :loading="extracting" @click="handleExtract">手动提取</el-button>
            <el-button size="small" :loading="consolidating" @click="handleConsolidate">整合记忆</el-button>
          </div>
        </div>

        <!-- 事实列表 -->
        <div class="section-header">
          <h4>记忆事实</h4>
          <el-button size="small" type="primary" @click="openCreateFactDialog">+ 新增事实</el-button>
        </div>

        <div class="category-filter">
          <el-radio-group v-model="factCategory" size="small">
            <el-radio-button
              v-for="opt in categoryOptions"
              :key="opt.value"
              :value="opt.value"
            >
              {{ opt.label }}
            </el-radio-button>
          </el-radio-group>
        </div>

        <el-table :data="facts" stripe>
          <el-table-column prop="factText" label="内容" />
          <el-table-column label="分类" width="100">
            <template #default="{ row }">
              {{ categoryLabelMap[row.category] || row.category }}
            </template>
          </el-table-column>
          <el-table-column prop="importance" label="重要性" width="80" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditFactDialog(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDeleteFact(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 偏好列表 -->
        <div class="section-header" style="margin-top: 20px;">
          <h4>用户偏好</h4>
          <el-button size="small" type="primary" @click="openCreatePrefDialog">+ 新增偏好</el-button>
        </div>

        <el-table :data="preferences" stripe>
          <el-table-column prop="preferenceKey" label="键" width="120" />
          <el-table-column prop="preferenceValue" label="值" />
          <el-table-column label="置信度" width="80">
            <template #default="{ row }">
              {{ row.confidence.toFixed(2) }}
            </template>
          </el-table-column>
          <el-table-column label="来源" width="80">
            <template #default="{ row }">
              {{ sourceLabelMap[row.source] || row.source }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="danger" @click="handleDeletePreference(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增/编辑事实对话框 -->
    <el-dialog
      v-model="factDialogVisible"
      :title="factDialogMode === 'create' ? '新增事实' : '编辑事实'"
      width="480px"
      append-to-body
    >
      <el-form label-width="80px">
        <el-form-item label="事实内容">
          <el-input
            v-model="factForm.factText"
            type="textarea"
            :rows="3"
            placeholder="例如：用户是一名后端工程师"
          />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="factForm.category" style="width: 100%;">
            <el-option label="个人信息" value="personal_info" />
            <el-option label="工作" value="work" />
            <el-option label="习惯" value="habit" />
            <el-option label="通用" value="general" />
          </el-select>
        </el-form-item>
        <el-form-item label="重要性">
          <el-slider v-model="factForm.importance" :min="1" :max="10" show-stops />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="factDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitFact">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新增偏好对话框 -->
    <el-dialog
      v-model="prefDialogVisible"
      title="新增偏好"
      width="480px"
      append-to-body
    >
      <el-form label-width="80px">
        <el-form-item label="偏好键">
          <el-input
            v-model="prefForm.preferenceKey"
            placeholder="例如：language"
          />
        </el-form-item>
        <el-form-item label="偏好值">
          <el-input
            v-model="prefForm.preferenceValue"
            placeholder="例如：Python"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="prefDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPreference">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.management-panel {
  padding: 20px;
}

.user-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--color-border);
}

.user-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.user-id-input :deep(.el-input__inner) {
  font-family: 'JetBrains Mono', 'Monaco', 'Menlo', monospace;
  font-size: 12px;
  color: var(--color-text);
}

.user-id-input :deep(.el-input-group__append) {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
}

.user-id-input :deep(.el-input-group__append:hover) {
  background: var(--color-primary-dark);
  border-color: var(--color-primary-dark);
}

.management-panel :deep(.el-tabs__item) {
  font-weight: 500;
}

.management-panel :deep(.el-tabs__item.is-active) {
  color: var(--color-primary);
}

.management-panel :deep(.el-tabs__active-bar) {
  background-color: var(--color-primary);
}

.tab-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.tab-header .el-button {
  background: var(--color-primary);
  border-color: var(--color-primary);
}

.hint {
  color: var(--color-text-secondary);
  font-size: 13px;
}

.management-panel h4 {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0;
}

.management-panel :deep(.el-table) {
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.management-panel :deep(.el-table th) {
  background: #F9FAFB;
  font-weight: 600;
}

.management-panel :deep(.el-switch.is-checked .el-switch__core) {
  background-color: var(--color-primary);
  border-color: var(--color-primary);
}

.memory-stats {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  margin-bottom: 16px;
  background: #F9FAFB;
  border-radius: var(--radius-sm);
}

.stats-text {
  font-size: 13px;
  color: var(--color-text-secondary);
}

.stats-actions {
  display: flex;
  gap: 8px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-header .el-button {
  background: var(--color-primary);
  border-color: var(--color-primary);
}

.category-filter {
  margin-bottom: 12px;
}

.category-filter :deep(.el-radio-button__inner) {
  font-size: 12px;
  padding: 6px 12px;
}

.category-filter :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background-color: var(--color-primary);
  border-color: var(--color-primary);
}
</style>
