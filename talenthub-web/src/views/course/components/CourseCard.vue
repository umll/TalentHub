<script setup lang="ts">
import { computed } from 'vue'
import { COURSE_STATUS, type Course } from '@/types/course'
import { formatDateTime } from '@/utils/format'
import CourseStatusTag from '@/components/CourseStatusTag.vue'
import EnrollCountdown from '@/components/EnrollCountdown.vue'

type StockLevel = 'normal' | 'warning' | 'danger'

const props = defineProps<{ course: Course; acting: boolean }>()
const emit = defineEmits<{ enroll: []; cancel: []; open: []; reached: [] }>()

const enrolledCount = computed(() => props.course.totalQuota - props.course.stock)

const enrollRatio = computed(() =>
  props.course.totalQuota === 0 ? 0 : enrolledCount.value / props.course.totalQuota
)

const stockLevel = computed<StockLevel>(() => {
  if (props.course.stock <= 0) return 'danger'
  if (props.course.stock < props.course.totalQuota * 0.3) return 'warning'
  return 'normal'
})

const stockText: Record<StockLevel, string> = {
  normal: '剩余充足',
  warning: '即将售罄',
  danger: '名额已满'
}

const barColor: Record<StockLevel, string> = {
  normal: 'rgb(var(--arcoblue-6))',
  warning: 'rgb(var(--orange-6))',
  danger: 'rgb(var(--red-6))'
}
</script>

<template>
  <div class="course-card panel micro-shadow hover-lift">
    <div class="card-status">
      <CourseStatusTag :status="course.status" />
    </div>
    <h3 class="card-title" @click="emit('open')">{{ course.title }}</h3>
    <div class="card-meta">
      <div class="meta-row">
        <icon-calendar />{{ formatDateTime(course.enrollStart) }} 开始报名
      </div>
      <div class="meta-row">
        <icon-clock-circle />{{ formatDateTime(course.enrollEnd) }} 截止
      </div>
    </div>
    <div class="card-bottom">
      <!-- 未开始：倒计时块 -->
      <template v-if="course.status === COURSE_STATUS.NOT_STARTED">
        <div class="countdown-box">
          <div class="countdown-label">距离开抢还有</div>
          <EnrollCountdown :target="course.enrollStart" @reached="emit('reached')" />
        </div>
        <a-button long disabled>开抢后可报名</a-button>
      </template>
      <template v-else>
        <div class="quota-line">
          <span>已报名 {{ enrolledCount }}/{{ course.totalQuota }}</span>
          <span class="quota-hint" :class="stockLevel">{{ stockText[stockLevel] }}</span>
        </div>
        <a-progress
          :percent="enrollRatio"
          :color="barColor[stockLevel]"
          :show-text="false"
          size="small"
        />
        <!-- 报名中：抢课 / 已报名可取消 -->
        <template v-if="course.status === COURSE_STATUS.OPEN">
          <a-popconfirm
            v-if="course.enrolled"
            content="确定取消报名吗？名额将释放给其他人。"
            @ok="emit('cancel')"
          >
            <a-button long status="success" :loading="acting">
              <template #icon><icon-check-circle /></template>
              已报名 · 点击取消
            </a-button>
          </a-popconfirm>
          <a-button
            v-else
            type="primary"
            long
            :disabled="course.stock <= 0"
            :loading="acting"
            @click="emit('enroll')"
          >
            <template #icon><icon-thunderbolt /></template>
            {{ course.stock > 0 ? '立即抢课' : '已抢完' }}
          </a-button>
        </template>
        <a-button v-else long disabled>
          {{ course.enrolled ? '已报名 · 课程已结束' : '报名已结束' }}
        </a-button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.course-card {
  position: relative;
  padding: 20px;
  display: flex;
  flex-direction: column;
}

.card-status {
  position: absolute;
  top: 16px;
  right: 16px;
}

.card-title {
  font-size: 17px;
  line-height: 25px;
  font-weight: 600;
  color: var(--color-text-1);
  margin: 0 72px 12px 0;
  cursor: pointer;
  transition: color 0.2s ease;
}

.card-title:hover {
  color: rgb(var(--arcoblue-6));
}

.card-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
  flex: 1;
  color: var(--color-text-2);
  font-size: 13px;
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.meta-row :deep(svg) {
  color: var(--color-text-3);
}

.card-bottom {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.quota-line {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--color-text-3);
}

.quota-hint.warning { color: rgb(var(--orange-6)); }
.quota-hint.danger { color: rgb(var(--red-6)); }

.countdown-box {
  background: rgb(var(--orange-1));
  border: 1px solid rgb(var(--orange-3));
  border-radius: 6px;
  padding: 10px;
  text-align: center;
}

.countdown-label {
  font-size: 12px;
  color: var(--color-text-3);
  margin-bottom: 2px;
}

.countdown-box :deep(.countdown) {
  font-size: 18px;
  font-weight: 600;
}
</style>
