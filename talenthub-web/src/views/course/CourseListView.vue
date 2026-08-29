<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { listCourses } from '@/api/course'
import { cancelEnroll, enroll } from '@/api/enrollment'
import { COURSE_STATUS, type Course } from '@/types/course'
import StatCard from '@/components/StatCard.vue'
import CourseCard from './components/CourseCard.vue'

const REFRESH_INTERVAL_MS = 5000

const router = useRouter()
const courses = ref<Course[]>([])
const loading = ref(false)
const actingCourseId = ref<number | null>(null)
const filter = ref<'all' | 'open' | 'notStarted'>('all')
const keyword = ref('')
let refreshTimer: number | undefined

const openCourses = computed(() => courses.value.filter((c) => c.status === COURSE_STATUS.OPEN))
const notStartedCount = computed(
  () => courses.value.filter((c) => c.status === COURSE_STATUS.NOT_STARTED).length
)
const totalStock = computed(() => openCourses.value.reduce((sum, c) => sum + c.stock, 0))

const filtered = computed(() =>
  courses.value.filter((c) => {
    if (filter.value === 'open' && c.status !== COURSE_STATUS.OPEN) return false
    if (filter.value === 'notStarted' && c.status !== COURSE_STATUS.NOT_STARTED) return false
    const kw = keyword.value.trim()
    return !kw || c.title.includes(kw)
  })
)

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
  <div class="course-list-page">
    <!-- 数据概览 -->
    <section class="stats-grid">
      <StatCard label="报名中课程" :value="openCourses.length" accent="success">
        <template #icon><icon-fire /></template>
      </StatCard>
      <StatCard label="未开始课程" :value="notStartedCount" accent="warning">
        <template #icon><icon-clock-circle /></template>
      </StatCard>
      <StatCard label="剩余名额总计" :value="totalStock" accent="primary">
        <template #icon><icon-user-group /></template>
      </StatCard>
    </section>

    <!-- 筛选与搜索 -->
    <section class="toolbar">
      <a-radio-group v-model="filter" type="button">
        <a-radio value="all">全部</a-radio>
        <a-radio value="open">报名中</a-radio>
        <a-radio value="notStarted">未开始</a-radio>
      </a-radio-group>
      <div class="toolbar-right">
        <a-input v-model="keyword" placeholder="搜索课程名称" allow-clear class="search-input">
          <template #prefix><icon-search /></template>
        </a-input>
        <span class="refresh-hint"><span class="refresh-dot" />每 5 秒静默刷新</span>
      </div>
    </section>

    <!-- 课程卡片流 -->
    <a-spin :loading="loading" class="grid-spin">
      <section v-if="filtered.length > 0" class="course-grid">
        <CourseCard
          v-for="c in filtered"
          :key="c.id"
          :course="c"
          :acting="actingCourseId === c.id"
          @open="router.push(`/courses/${c.id}`)"
          @enroll="onEnroll(c)"
          @cancel="onCancel(c)"
          @reached="reload()"
        />
      </section>
      <div v-else-if="!loading" class="panel micro-shadow empty-panel">
        <a-empty description="没有符合条件的课程" />
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
.course-list-page {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.search-input {
  width: 240px;
}

.grid-spin {
  display: block;
  width: 100%;
}

.course-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
}

.empty-panel {
  padding: 48px 0;
}

@media (max-width: 960px) {
  .stats-grid,
  .course-grid {
    grid-template-columns: 1fr;
  }
}
</style>
