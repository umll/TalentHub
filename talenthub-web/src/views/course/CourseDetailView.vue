<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { getCourse } from '@/api/course'
import { cancelEnroll, enroll } from '@/api/enrollment'
import { COURSE_STATUS, type Course } from '@/types/course'
import { formatDateTime } from '@/utils/format'
import CourseStatusTag from '@/components/CourseStatusTag.vue'
import StockBadge from '@/components/StockBadge.vue'
import EnrollCountdown from '@/components/EnrollCountdown.vue'

const route = useRoute()
const router = useRouter()
const courseId = Number(route.params.id)

const course = ref<Course | null>(null)
const acting = ref(false)

async function reload() {
  try {
    course.value = await getCourse(courseId)
  } catch {
    // 错误提示已由 request 统一处理
  }
}

async function onEnroll() {
  acting.value = true
  try {
    const result = await enroll(courseId)
    Message.success(result.alreadyEnrolled ? '您已报名该课程' : '报名成功')
  } catch {
    // 失败原因已统一弹出
  } finally {
    acting.value = false
    await reload()
  }
}

async function onCancel() {
  acting.value = true
  try {
    await cancelEnroll(courseId)
    Message.success('已取消报名')
  } catch {
    // 错误提示已由 request 统一处理
  } finally {
    acting.value = false
    await reload()
  }
}

onMounted(reload)
</script>

<template>
  <a-card v-if="course" :title="course.title">
    <template #extra>
      <a-button size="small" @click="router.back()">返回</a-button>
    </template>
    <a-descriptions :column="1" bordered>
      <a-descriptions-item label="状态">
        <a-space>
          <CourseStatusTag :status="course.status" />
          <EnrollCountdown
            v-if="course.status === COURSE_STATUS.NOT_STARTED"
            :target="course.enrollStart"
            @reached="reload()"
          />
        </a-space>
      </a-descriptions-item>
      <a-descriptions-item label="剩余名额">
        <StockBadge :stock="course.stock" :total="course.totalQuota" />
      </a-descriptions-item>
      <a-descriptions-item label="报名开始">{{ formatDateTime(course.enrollStart) }}</a-descriptions-item>
      <a-descriptions-item label="报名截止">{{ formatDateTime(course.enrollEnd) }}</a-descriptions-item>
    </a-descriptions>
    <div class="detail-actions">
      <template v-if="course.status === COURSE_STATUS.OPEN">
        <a-popconfirm
          v-if="course.enrolled"
          content="确定取消报名吗？名额将释放给其他人。"
          @ok="onCancel"
        >
          <a-button :loading="acting">取消报名</a-button>
        </a-popconfirm>
        <a-button
          v-else
          type="primary"
          :disabled="course.stock <= 0"
          :loading="acting"
          @click="onEnroll"
        >
          {{ course.stock > 0 ? '立即抢课' : '已抢完' }}
        </a-button>
      </template>
      <a-tag v-else-if="course.enrolled" color="green">已报名</a-tag>
    </div>
  </a-card>
</template>

<style scoped>
.detail-actions {
  margin-top: 16px;
}
</style>
