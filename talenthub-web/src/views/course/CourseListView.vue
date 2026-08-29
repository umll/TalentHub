<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { listCourses } from '@/api/course'
import { cancelEnroll, enroll } from '@/api/enrollment'
import { COURSE_STATUS, type Course } from '@/types/course'
import { formatDateTime } from '@/utils/format'
import CourseStatusTag from '@/components/CourseStatusTag.vue'
import StockBadge from '@/components/StockBadge.vue'
import EnrollCountdown from '@/components/EnrollCountdown.vue'

const REFRESH_INTERVAL_MS = 5000

const router = useRouter()
const courses = ref<Course[]>([])
const loading = ref(false)
const actingCourseId = ref<number | null>(null)
let refreshTimer: number | undefined

async function reload(showLoading = false) {
  if (showLoading) loading.value = true
  try {
    courses.value = await listCourses()
  } catch {
    // 错误提示已由 request 统一处理
  } finally {
    loading.value = false
  }
}

async function onEnroll(course: Course) {
  actingCourseId.value = course.id
  try {
    const result = await enroll(course.id)
    if (result.alreadyEnrolled) {
      Message.info('您已报名该课程')
    } else {
      Message.success('报名成功')
    }
  } catch {
    // 失败原因（名额已满/限流等）已统一弹出
  } finally {
    actingCourseId.value = null
    await reload()
  }
}

async function onCancel(course: Course) {
  actingCourseId.value = course.id
  try {
    await cancelEnroll(course.id)
    Message.success('已取消报名')
  } catch {
    // 错误提示已由 request 统一处理
  } finally {
    actingCourseId.value = null
    await reload()
  }
}

onMounted(() => {
  reload(true)
  refreshTimer = window.setInterval(() => reload(), REFRESH_INTERVAL_MS)
})
onUnmounted(() => {
  if (refreshTimer !== undefined) {
    window.clearInterval(refreshTimer)
  }
})
</script>

<template>
  <a-card title="培训课程">
    <template #extra>
      <a-button size="small" @click="reload(true)">刷新</a-button>
    </template>
    <a-table :data="courses" :loading="loading" :pagination="false" row-key="id">
      <template #columns>
        <a-table-column title="课程" data-index="title">
          <template #cell="{ record }">
            <a-link @click="router.push(`/courses/${record.id}`)">{{ record.title }}</a-link>
          </template>
        </a-table-column>
        <a-table-column title="剩余名额" :width="120">
          <template #cell="{ record }">
            <StockBadge :stock="record.stock" :total="record.totalQuota" />
          </template>
        </a-table-column>
        <a-table-column title="报名时间" :width="320">
          <template #cell="{ record }">
            {{ formatDateTime(record.enrollStart) }} ~ {{ formatDateTime(record.enrollEnd) }}
          </template>
        </a-table-column>
        <a-table-column title="状态" :width="150">
          <template #cell="{ record }">
            <a-space>
              <CourseStatusTag :status="record.status" />
              <EnrollCountdown
                v-if="record.status === COURSE_STATUS.NOT_STARTED"
                :target="record.enrollStart"
                @reached="reload()"
              />
            </a-space>
          </template>
        </a-table-column>
        <a-table-column title="操作" :width="140">
          <template #cell="{ record }">
            <template v-if="record.status === COURSE_STATUS.OPEN">
              <a-popconfirm
                v-if="record.enrolled"
                content="确定取消报名吗？名额将释放给其他人。"
                @ok="onCancel(record)"
              >
                <a-button size="small" :loading="actingCourseId === record.id">取消报名</a-button>
              </a-popconfirm>
              <a-button
                v-else
                type="primary"
                size="small"
                :disabled="record.stock <= 0"
                :loading="actingCourseId === record.id"
                @click="onEnroll(record)"
              >
                {{ record.stock > 0 ? '抢课' : '已抢完' }}
              </a-button>
            </template>
            <a-tag v-else-if="record.enrolled" color="green">已报名</a-tag>
          </template>
        </a-table-column>
      </template>
    </a-table>
  </a-card>
</template>
