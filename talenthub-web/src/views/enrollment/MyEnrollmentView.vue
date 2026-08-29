<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { cancelEnroll, listMyEnrollments } from '@/api/enrollment'
import { COURSE_STATUS } from '@/types/course'
import { ENROLLMENT_STATUS, type Enrollment } from '@/types/enrollment'
import { formatDateTime } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import StatCard from '@/components/StatCard.vue'

const router = useRouter()
const userStore = useUserStore()
const enrollments = ref<Enrollment[]>([])
const loading = ref(false)
const actingCourseId = ref<number | null>(null)

const activeCount = computed(
  () => enrollments.value.filter((e) => e.status === ENROLLMENT_STATUS.ENROLLED).length
)
const canceledCount = computed(
  () => enrollments.value.filter((e) => e.status === ENROLLMENT_STATUS.CANCELED).length
)

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

function isEnrolled(item: Enrollment): boolean {
  return item.status === ENROLLMENT_STATUS.ENROLLED
}

function canCancel(item: Enrollment): boolean {
  return isEnrolled(item) && item.courseStatus === COURSE_STATUS.OPEN
}

onMounted(reload)
// 切换演示用户后自动刷新为新用户的数据
watch(() => userStore.current.id, reload)
</script>

<template>
  <div class="my-page">
    <div>
      <h1 class="page-title">我的报名</h1>
      <p class="page-subtitle">管理您的个人培训课程报名记录</p>
    </div>

    <!-- 统计卡 -->
    <section class="stats-grid">
      <StatCard label="累计报名课程" :value="enrollments.length" accent="primary">
        <template #icon><icon-bookmark /></template>
      </StatCard>
      <StatCard label="当前已报名" :value="activeCount" accent="success">
        <template #icon><icon-check-circle /></template>
      </StatCard>
      <StatCard label="已取消" :value="canceledCount">
        <template #icon><icon-close-circle /></template>
      </StatCard>
    </section>

    <!-- 报名记录 -->
    <div class="panel micro-shadow record-panel">
      <div class="record-header">
        <h2 class="record-title">报名记录</h2>
        <a-button size="small" @click="reload">
          <template #icon><icon-refresh /></template>
          刷新
        </a-button>
      </div>

      <a-table
        v-if="loading || enrollments.length > 0"
        :data="enrollments"
        :loading="loading"
        :pagination="false"
        row-key="id"
      >
        <template #columns>
          <a-table-column title="课程" data-index="courseTitle" />
          <a-table-column title="状态" :width="110">
            <template #cell="{ record }">
              <span v-if="isEnrolled(record)" class="status-pill success">
                <span class="pill-dot" />已报名
              </span>
              <span v-else class="status-pill gray">已取消</span>
            </template>
          </a-table-column>
          <a-table-column title="报名时间" :width="180">
            <template #cell="{ record }">
              <span class="time-cell">{{ formatDateTime(record.enrolledAt) }}</span>
            </template>
          </a-table-column>
          <a-table-column title="取消时间" :width="180">
            <template #cell="{ record }">
              <span class="time-cell">{{ formatDateTime(record.canceledAt) }}</span>
            </template>
          </a-table-column>
          <a-table-column title="操作" :width="120" align="right">
            <template #cell="{ record }">
              <a-popconfirm
                v-if="canCancel(record)"
                content="确定取消报名吗？名额将释放给其他人。"
                @ok="onCancel(record)"
              >
                <a-link :loading="actingCourseId === record.courseId">取消报名</a-link>
              </a-popconfirm>
              <a-tooltip
                v-else-if="isEnrolled(record)"
                content="报名已截止，无法取消"
              >
                <span class="disabled-action">取消报名</span>
              </a-tooltip>
              <span v-else class="time-cell">-</span>
            </template>
          </a-table-column>
        </template>
      </a-table>

      <!-- 空状态 -->
      <div v-else class="empty-state">
        <a-empty description="暂无报名记录" />
        <p class="empty-hint">您还没有报名任何培训课程，去课程列表看看有没有感兴趣的课程吧。</p>
        <a-button type="primary" @click="router.push('/')">去抢课</a-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.my-page {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
}

.record-panel {
  overflow: hidden;
}

.record-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border-1);
}

.record-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-1);
  margin: 0;
}

.time-cell {
  color: var(--color-text-3);
  font-variant-numeric: tabular-nums;
}

.disabled-action {
  color: var(--color-text-4);
  cursor: not-allowed;
  font-size: 14px;
}

.empty-state {
  padding: 48px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.empty-hint {
  color: var(--color-text-3);
  font-size: 13px;
  margin: 0;
}

@media (max-width: 960px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }
}
</style>
