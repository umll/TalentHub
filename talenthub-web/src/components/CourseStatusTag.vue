<script setup lang="ts">
import { computed } from 'vue'
import { COURSE_STATUS } from '@/types/course'

const props = defineProps<{ status: number }>()

const meta = computed(() => {
  switch (props.status) {
    case COURSE_STATUS.NOT_STARTED:
      return { color: 'gray', text: '未开始' }
    case COURSE_STATUS.OPEN:
      return { color: 'green', text: '报名中' }
    case COURSE_STATUS.ENDED:
      return { color: 'red', text: '已结束' }
    case COURSE_STATUS.CANCELED:
      return { color: 'orange', text: '已取消' }
    default:
      return { color: 'gray', text: '未知' }
  }
})
</script>

<template>
  <a-tag :color="meta.color">{{ meta.text }}</a-tag>
</template>
