<script setup lang="ts">
import { computed } from 'vue'
import { COURSE_STATUS } from '@/types/course'

const props = defineProps<{ status: number }>()

const meta = computed(() => {
  switch (props.status) {
    case COURSE_STATUS.NOT_STARTED:
      return { cls: 'gray', text: '未开始', dot: false }
    case COURSE_STATUS.OPEN:
      return { cls: 'success', text: '报名中', dot: true }
    case COURSE_STATUS.ENDED:
      return { cls: 'danger', text: '已结束', dot: false }
    case COURSE_STATUS.CANCELED:
      return { cls: 'warning', text: '已取消', dot: false }
    default:
      return { cls: 'gray', text: '未知', dot: false }
  }
})
</script>

<template>
  <span class="status-pill" :class="meta.cls">
    <span v-if="meta.dot" class="pill-dot" />{{ meta.text }}
  </span>
</template>
