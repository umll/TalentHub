<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { getCourse } from '@/api/course'
import { cancelEnroll, enroll } from '@/api/enrollment'
import { COURSE_STATUS, type Course } from '@/types/course'
import { formatDateTime } from '@/utils/format'
import CourseStatusTag from '@/components/CourseStatusTag.vue'
import EnrollCountdown from '@/components/EnrollCountdown.vue'

type StockLevel = 'normal' | 'warning' | 'danger'

const route = useRoute()
const courseId = Number(route.params.id)

const course = ref<Course | null>(null)
const acting = ref(false)

const enrolledCount = computed(() =>
  course.value ? course.value.totalQuota - course.value.stock : 0
)

const enrollRatio = computed(() => {
  if (!course.value || course.value.totalQuota === 0) return 0
  return enrolledCount.value / course.value.totalQuota
})

const stockLevel = computed<StockLevel>(() => {
  if (!course.value || course.value.stock <= 0) return 'danger'
  if (course.value.stock < course.value.totalQuota * 0.3) return 'warning'
  return 'normal'
})

const ratioColor: Record<StockLevel, string> = {
  normal: 'rgb(var(--arcoblue-6))',
  warning: 'rgb(var(--orange-6))',
  danger: 'rgb(var(--red-6))'
}

const actionTitle = computed(() => {
  if (!course.value) return ''
  switch (course.value.status) {
    case COURSE_STATUS.NOT_STARTED:
      return '报名尚未开始'
    case COURSE_STATUS.OPEN:
      if (course.value.stock <= 0) return '名额已满'
      return stockLevel.value === 'warning' ? '名额紧张，即将报满！' : '名额充足，可放心报名'
    default:
      return '报名已结束'
  }
})

/** 抢课流程当前步骤：未开始=预热中(1)，报名中=开放报名(2)，其余=已截止(3) */
const currentStep = computed(() => {
  if (!course.value) return 1
  if (course.value.status === COURSE_STATUS.NOT_STARTED) return 1
  if (course.value.status === COURSE_STATUS.OPEN) return 2
  return 3
})

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
  <div v-if="course" class="detail-page">
    <a-breadcrumb class="crumbs">
      <a-breadcrumb-item>
        <router-link to="/">课程列表</router-link>
      </a-breadcrumb-item>
      <a-breadcrumb-item>课程详情</a-breadcrumb-item>
    </a-breadcrumb>

    <div class="detail-grid">
      <!-- 左：主信息卡 -->
      <div class="detail-main panel micro-shadow">
        <div class="pills">
          <CourseStatusTag :status="course.status" />
          <span class="status-pill primary">内部培训</span>
        </div>
        <h1 class="detail-title">{{ course.title }}</h1>

        <div class="info-grid">
          <div>
            <p class="info-label">报名开始</p>
            <p class="info-value">{{ formatDateTime(course.enrollStart) }}</p>
          </div>
          <div>
            <p class="info-label">报名截止</p>
            <p class="info-value">{{ formatDateTime(course.enrollEnd) }}</p>
          </div>
          <div>
            <p class="info-label">总名额</p>
            <p class="info-value">{{ course.totalQuota }} 人</p>
          </div>
          <div>
            <p class="info-label">我的状态</p>
            <p class="info-value" :class="{ 'value-enrolled': course.enrolled }">
              {{ course.enrolled ? '已报名' : '未报名' }}
            </p>
          </div>
        </div>

        <div class="action-bar">
          <div class="action-left">
            <a-progress
              type="circle"
              :percent="enrollRatio"
              :color="ratioColor[stockLevel]"
              size="small"
            />
            <div>
              <p class="action-title">{{ actionTitle }}</p>
              <p class="action-sub">
                剩余名额
                <b class="stock-num" :class="stockLevel">{{ course.stock }}</b>
                / 总 {{ course.totalQuota }}
              </p>
            </div>
          </div>
          <div class="action-right">
            <template v-if="course.status === COURSE_STATUS.NOT_STARTED">
              <div class="countdown-box">
                <div class="countdown-label">距离开抢还有</div>
                <EnrollCountdown :target="course.enrollStart" @reached="reload()" />
              </div>
            </template>
            <template v-else-if="course.status === COURSE_STATUS.OPEN">
              <a-popconfirm
                v-if="course.enrolled"
                content="确定取消报名吗？名额将释放给其他人。"
                @ok="onCancel"
              >
                <a-button size="large" status="success" :loading="acting">
                  <template #icon><icon-check-circle /></template>
                  已报名 · 点击取消
                </a-button>
              </a-popconfirm>
              <a-button
                v-else
                type="primary"
                size="large"
                class="cta-btn"
                :disabled="course.stock <= 0"
                :loading="acting"
                @click="onEnroll"
              >
                <template #icon><icon-thunderbolt /></template>
                {{ course.stock > 0 ? '立即抢课' : '已抢完' }}
              </a-button>
            </template>
            <a-tag v-else-if="course.enrolled" color="green" size="large">已报名</a-tag>
          </div>
        </div>
      </div>

      <!-- 右：抢课流程与说明 -->
      <aside class="detail-side">
        <div class="panel micro-shadow side-card">
          <h3 class="side-title">抢课流程</h3>
          <a-steps direction="vertical" :current="currentStep" small>
            <a-step title="课程预热" description="开抢前 5 分钟系统自动完成库存预热" />
            <a-step title="开放报名" :description="`${formatDateTime(course.enrollStart)} 开抢，先到先得`" />
            <a-step title="报名截止" :description="formatDateTime(course.enrollEnd)" />
          </a-steps>
        </div>
        <div class="panel micro-shadow side-card">
          <h3 class="side-title">温馨提示</h3>
          <ul class="tips">
            <li>同一课程每人仅可报名一次，重复点击不会重复占用名额。</li>
            <li>取消报名后名额立即释放，可再次报名。</li>
            <li>高峰期若提示「人数过多」，稍后重试即可。</li>
          </ul>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.detail-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 24px;
  align-items: start;
}

.detail-main {
  padding: 32px;
}

.pills {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.detail-title {
  font-size: 28px;
  line-height: 38px;
  font-weight: 700;
  color: var(--color-text-1);
  margin: 0 0 24px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  padding-top: 20px;
  border-top: 1px solid var(--color-border-1);
  margin-bottom: 24px;
}

.info-label {
  font-size: 12px;
  color: var(--color-text-3);
  margin: 0 0 4px;
}

.info-value {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-1);
  margin: 0;
}

.value-enrolled {
  color: rgb(var(--green-6));
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  background: var(--color-fill-1);
  border: 1px solid var(--color-border-1);
  border-radius: 8px;
  padding: 20px 24px;
  flex-wrap: wrap;
}

.action-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.action-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-1);
  margin: 0 0 4px;
}

.action-sub {
  font-size: 13px;
  color: var(--color-text-3);
  margin: 0;
}

.stock-num {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.stock-num.normal { color: rgb(var(--arcoblue-6)); }
.stock-num.warning { color: rgb(var(--orange-6)); }
.stock-num.danger { color: rgb(var(--red-6)); }

.cta-btn {
  box-shadow: 0 4px 14px rgba(22, 93, 255, 0.35);
  padding-left: 32px;
  padding-right: 32px;
}

.countdown-box {
  background: rgb(var(--orange-1));
  border: 1px solid rgb(var(--orange-3));
  border-radius: 6px;
  padding: 10px 24px;
  text-align: center;
}

.countdown-label {
  font-size: 12px;
  color: var(--color-text-3);
  margin-bottom: 2px;
}

.countdown-box :deep(.countdown) {
  font-size: 20px;
  font-weight: 600;
}

.detail-side {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.side-card {
  padding: 24px;
}

.side-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-1);
  margin: 0 0 16px;
}

.tips {
  margin: 0;
  padding-left: 18px;
  color: var(--color-text-2);
  font-size: 13px;
  line-height: 22px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

@media (max-width: 960px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .info-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
