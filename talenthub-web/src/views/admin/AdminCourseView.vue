<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { listCourses } from '@/api/course'
import { createCourse, listReconcileLogs, preheatCourse, updateCourse } from '@/api/admin'
import { COURSE_STATUS, type Course, type ReconcileLog } from '@/types/course'
import { formatDateTime } from '@/utils/format'
import CourseStatusTag from '@/components/CourseStatusTag.vue'

const courses = ref<Course[]>([])
const logs = ref<ReconcileLog[]>([])
const loading = ref(false)
const actingCourseId = ref<number | null>(null)

const modalVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  title: '',
  totalQuota: 20,
  timeRange: [] as string[]
})

/** 对账修正动作 → 胶囊颜色 */
const ACTION_PILL: Record<string, string> = {
  FIX_REDIS_HIGH: 'warning',
  FIX_REDIS_LOW: 'primary',
  DB_SELF_INCONSISTENT: 'danger'
}

function actionPill(action: string): string {
  return ACTION_PILL[action] ?? 'gray'
}

function enrolledCount(course: Course): number {
  return course.totalQuota - course.stock
}

async function reloadCourses() {
  loading.value = true
  try {
    courses.value = await listCourses()
  } catch {
    // 错误提示已由 request 统一处理
  } finally {
    loading.value = false
  }
}

async function reloadLogs() {
  try {
    logs.value = await listReconcileLogs()
  } catch {
    // 错误提示已由 request 统一处理
  }
}

function openCreate() {
  editingId.value = null
  form.title = ''
  form.totalQuota = 20
  form.timeRange = []
  modalVisible.value = true
}

function openEdit(course: Course) {
  editingId.value = course.id
  form.title = course.title
  form.totalQuota = course.totalQuota
  form.timeRange = [formatDateTime(course.enrollStart), formatDateTime(course.enrollEnd)]
  modalVisible.value = true
}

/** a-range-picker 返回本地时间字符串，转为带时区的 ISO 供后端 OffsetDateTime 解析 */
function toIso(local: string): string {
  return new Date(local.replace(' ', 'T')).toISOString()
}

async function onSubmit() {
  if (!form.title.trim() || form.timeRange.length !== 2) {
    Message.warning('请填写课程名称与报名时间段')
    return
  }
  submitting.value = true
  const payload = {
    title: form.title.trim(),
    totalQuota: form.totalQuota,
    enrollStart: toIso(form.timeRange[0]),
    enrollEnd: toIso(form.timeRange[1])
  }
  try {
    if (editingId.value === null) {
      await createCourse(payload)
      Message.success('课程已创建')
    } else {
      await updateCourse(editingId.value, payload)
      Message.success('课程已更新')
    }
    modalVisible.value = false
    await reloadCourses()
  } catch {
    // 错误提示已由 request 统一处理
  } finally {
    submitting.value = false
  }
}

async function onPreheat(course: Course) {
  actingCourseId.value = course.id
  try {
    await preheatCourse(course.id)
    Message.success('预热完成：Redis 库存已按 DB 重建')
    await reloadCourses()
  } catch {
    // 错误提示已由 request 统一处理
  } finally {
    actingCourseId.value = null
  }
}

onMounted(() => {
  reloadCourses()
  reloadLogs()
})
</script>

<template>
  <div class="admin-page">
    <div>
      <h1 class="page-title">课程管理与库存审计</h1>
      <p class="page-subtitle">课程的创建、名额调整、库存预热，以及 Redis 与 DB 的对账修正记录</p>
    </div>

    <a-tabs default-active-key="courses">
      <a-tab-pane key="courses" title="课程管理">
        <div class="tab-body">
          <div class="tab-toolbar">
            <a-button type="primary" @click="openCreate">
              <template #icon><icon-plus /></template>
              新建课程
            </a-button>
            <a-button @click="reloadCourses">
              <template #icon><icon-refresh /></template>
              刷新
            </a-button>
          </div>

          <div class="panel micro-shadow table-panel">
            <a-table :data="courses" :loading="loading" :pagination="false" row-key="id">
              <template #columns>
                <a-table-column title="ID" data-index="id" :width="70" />
                <a-table-column title="课程" data-index="title" />
                <a-table-column title="总名额" :width="90" align="right">
                  <template #cell="{ record }">{{ record.totalQuota }}</template>
                </a-table-column>
                <a-table-column title="已报名" :width="90" align="right">
                  <template #cell="{ record }">
                    <span class="num-cell">{{ enrolledCount(record) }}</span>
                  </template>
                </a-table-column>
                <a-table-column title="报名时间" :width="300">
                  <template #cell="{ record }">
                    <span class="time-cell">
                      {{ formatDateTime(record.enrollStart) }} ~ {{ formatDateTime(record.enrollEnd) }}
                    </span>
                  </template>
                </a-table-column>
                <a-table-column title="状态" :width="100">
                  <template #cell="{ record }">
                    <CourseStatusTag :status="record.status" />
                  </template>
                </a-table-column>
                <a-table-column title="操作" :width="140">
                  <template #cell="{ record }">
                    <a-space>
                      <a-link
                        v-if="record.status === COURSE_STATUS.NOT_STARTED"
                        @click="openEdit(record)"
                      >
                        编辑
                      </a-link>
                      <a-tooltip
                        v-if="record.status !== COURSE_STATUS.ENDED && record.status !== COURSE_STATUS.CANCELED"
                        content="将库存加载至 Redis"
                      >
                        <a-link
                          status="warning"
                          :loading="actingCourseId === record.id"
                          @click="onPreheat(record)"
                        >
                          预热
                        </a-link>
                      </a-tooltip>
                    </a-space>
                  </template>
                </a-table-column>
              </template>
            </a-table>
          </div>
        </div>
      </a-tab-pane>

      <a-tab-pane key="logs" title="对账记录">
        <div class="tab-body">
          <div class="tab-toolbar">
            <a-button @click="reloadLogs">
              <template #icon><icon-refresh /></template>
              刷新审计
            </a-button>
          </div>

          <div class="panel micro-shadow table-panel">
            <a-table :data="logs" :pagination="false" row-key="id">
              <template #columns>
                <a-table-column title="校验时间" :width="180">
                  <template #cell="{ record }">
                    <span class="time-cell">{{ formatDateTime(record.createdAt) }}</span>
                  </template>
                </a-table-column>
                <a-table-column title="课程" data-index="courseTitle" />
                <a-table-column title="Redis 库存" :width="110" align="right">
                  <template #cell="{ record }">
                    <span class="num-cell">{{ record.redisStock ?? '-' }}</span>
                  </template>
                </a-table-column>
                <a-table-column title="DB 库存" :width="100" align="right">
                  <template #cell="{ record }">
                    <span class="num-cell">{{ record.dbStock }}</span>
                  </template>
                </a-table-column>
                <a-table-column title="修正动作" :width="220">
                  <template #cell="{ record }">
                    <span class="status-pill" :class="actionPill(record.action)">
                      {{ record.action }}
                    </span>
                  </template>
                </a-table-column>
              </template>
              <template #empty>
                <div class="reconcile-empty">
                  <icon-check-circle class="reconcile-empty-icon" />
                  <p>Redis 与 DB 库存全部一致，暂无修正记录</p>
                </div>
              </template>
            </a-table>
          </div>
        </div>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      v-model:visible="modalVisible"
      :title="editingId === null ? '新建课程' : '编辑课程'"
      :ok-loading="submitting"
      @ok="onSubmit"
    >
      <a-form :model="form" layout="vertical">
        <a-form-item label="课程名称" required>
          <a-input v-model="form.title" placeholder="请输入课程名称" />
        </a-form-item>
        <a-form-item label="总名额" required>
          <a-input-number v-model="form.totalQuota" :min="1" :max="10000" />
        </a-form-item>
        <a-form-item label="报名时间段" required>
          <a-range-picker v-model="form.timeRange" show-time format="YYYY-MM-DD HH:mm:ss" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.admin-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.tab-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: 8px;
}

.tab-toolbar {
  display: flex;
  gap: 8px;
}

.table-panel {
  overflow: hidden;
  padding: 4px;
}

.time-cell,
.num-cell {
  color: var(--color-text-3);
  font-variant-numeric: tabular-nums;
}

.num-cell {
  color: var(--color-text-1);
}

.reconcile-empty {
  padding: 40px 0;
  text-align: center;
  color: var(--color-text-3);
}

.reconcile-empty-icon {
  font-size: 40px;
  color: rgb(var(--green-6));
  margin-bottom: 8px;
}
</style>
