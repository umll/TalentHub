<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ stock: number; total: number }>()

const color = computed(() => {
  if (props.stock <= 0) return 'red'
  if (props.stock < props.total * 0.3) return 'orange'
  return 'arcoblue'
})
</script>

<template>
  <a-tag :color="color">{{ stock }} / {{ total }}</a-tag>
</template>
