<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { listCourses } from '@/api/course'
import { createCourse, listReconcileLogs, preheatCourse, updateCourse } from '@/api/admin'
import { COURSE_STATUS, type Course, type ReconcileLog } from '@/types/course'
import { formatDateTime } from '@/utils/format'
import CourseStatusTag from '@/components/CourseStatusTag.vue'
import StockBadge from '@/components/StockBadge.vue'

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
  <a-tabs default-active-key="courses">
    <a-tab-pane key="courses" title="课程管理">
      <a-card>
        <template #extra>
          <a-space>
            <a-button size="small" @click="reloadCourses">刷新</a-button>
            <a-button type="primary" size="small" @click="openCreate">新建课程</a-button>
          </a-space>
        </template>
        <a-table :data="courses" :loading="loading" :pagination="false" row-key="id">
          <template #columns>
            <a-table-column title="ID" data-index="id" :width="60" />
            <a-table-column title="课程" data-index="title" />
            <a-table-column title="名额" :width="110">
              <template #cell="{ record }">
                <StockBadge :stock="record.stock" :total="record.totalQuota" />
              </template>
            </a-table-column>
            <a-table-column title="报名时间" :width="300">
              <template #cell="{ record }">
                {{ formatDateTime(record.enrollStart) }} ~ {{ formatDateTime(record.enrollEnd) }}
              </template>
            </a-table-column>
            <a-table-column title="状态" :width="90">
              <template #cell="{ record }">
                <CourseStatusTag :status="record.status" />
              </template>
            </a-table-column>
            <a-table-column title="操作" :width="160">
              <template #cell="{ record }">
                <a-space>
                  <a-button
                    v-if="record.status === COURSE_STATUS.NOT_STARTED"
                    size="small"
                    @click="openEdit(record)"
                  >
                    编辑
                  </a-button>
                  <a-button
                    v-if="record.status !== COURSE_STATUS.ENDED && record.status !== COURSE_STATUS.CANCELED"
                    size="small"
                    :loading="actingCourseId === record.id"
                    @click="onPreheat(record)"
                  >
                    预热
                  </a-button>
                </a-space>
              </template>
            </a-table-column>
          </template>
        </a-table>
      </a-card>
    </a-tab-pane>

    <a-tab-pane key="logs" title="对账记录">
      <a-card>
        <template #extra>
          <a-button size="small" @click="reloadLogs">刷新</a-button>
        </template>
        <a-table :data="logs" :pagination="false" row-key="id">
          <template #columns>
            <a-table-column title="课程" data-index="courseTitle" />
            <a-table-column title="Redis 库存" data-index="redisStock" :width="110" />
            <a-table-column title="DB 库存" data-index="dbStock" :width="100" />
            <a-table-column title="动作" data-index="action" :width="200" />
            <a-table-column title="时间" :width="180">
              <template #cell="{ record }">{{ formatDateTime(record.createdAt) }}</template>
            </a-table-column>
          </template>
        </a-table>
      </a-card>
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
</template>
