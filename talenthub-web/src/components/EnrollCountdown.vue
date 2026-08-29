<script setup lang="ts">
import { watch } from 'vue'
import { useCountdown } from '@/composables/useCountdown'

const props = defineProps<{ target: string }>()
const emit = defineEmits<{ reached: [] }>()

const { text, reached } = useCountdown(() => props.target)

watch(reached, (value) => {
  if (value) {
    emit('reached')
  }
})
</script>

<template>
  <span v-if="text" class="countdown">{{ text }} 后开抢</span>
</template>

<style scoped>
.countdown {
  color: rgb(var(--orange-6));
  font-variant-numeric: tabular-nums;
}
</style>
