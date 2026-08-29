<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { cancelEnroll, listMyEnrollments } from '@/api/enrollment'
import { COURSE_STATUS } from '@/types/course'
import { ENROLLMENT_STATUS, type Enrollment } from '@/types/enrollment'
import { formatDateTime } from '@/utils/format'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const enrollments = ref<Enrollment[]>([])
const loading = ref(false)
const actingCourseId = ref<number | null>(null)

async function reload() {
  loading.value = true
  try {
    enrollments.value = await listMyEnrollments()
  } catch {
    // 错误提示已由 request 统一处理
  } finally {
    loading.value = false
  }
}

async function onCancel(item: Enrollment) {
  actingCourseId.value = item.courseId
  try {
    await cancelEnroll(item.courseId)
    Message.success('已取消报名')
  } catch {
    // 错误提示已由 request 统一处理
  } finally {
    actingCourseId.value = null
    await reload()
  }
}

function canCancel(item: Enrollment): boolean {
  return item.status === ENROLLMENT_STATUS.ENROLLED && item.courseStatus === COURSE_STATUS.OPEN
}

onMounted(reload)
// 切换演示用户后自动刷新为新用户的数据
watch(() => userStore.current.id, reload)
</script>

<template>
  <a-card title="我的报名">
    <template #extra>
      <a-button size="small" @click="reload">刷新</a-button>
    </template>
    <a-table :data="enrollments" :loading="loading" :pagination="false" row-key="id">
      <template #columns>
        <a-table-column title="课程" data-index="courseTitle" />
        <a-table-column title="状态" :width="100">
          <template #cell="{ record }">
            <a-tag v-if="record.status === ENROLLMENT_STATUS.ENROLLED" color="green">已报名</a-tag>
            <a-tag v-else color="gray">已取消</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="报名时间" :width="180">
          <template #cell="{ record }">{{ formatDateTime(record.enrolledAt) }}</template>
        </a-table-column>
        <a-table-column title="取消时间" :width="180">
          <template #cell="{ record }">{{ formatDateTime(record.canceledAt) }}</template>
        </a-table-column>
        <a-table-column title="操作" :width="120">
          <template #cell="{ record }">
            <a-popconfirm
              v-if="canCancel(record)"
              content="确定取消报名吗？名额将释放给其他人。"
              @ok="onCancel(record)"
            >
              <a-button size="small" :loading="actingCourseId === record.courseId">取消报名</a-button>
            </a-popconfirm>
          </template>
        </a-table-column>
      </template>
    </a-table>
  </a-card>
</template>
