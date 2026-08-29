<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string | number
    accent?: 'default' | 'primary' | 'success' | 'warning'
  }>(),
  { accent: 'default' }
)
</script>

<template>
  <div class="stat-card panel micro-shadow hover-lift">
    <div v-if="$slots.icon" class="stat-icon" :class="`icon-${accent}`">
      <slot name="icon" />
    </div>
    <div>
      <div class="stat-label">{{ label }}</div>
      <div class="stat-value" :class="`value-${accent}`">{{ value }}</div>
    </div>
  </div>
</template>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}

.icon-default {
  background: var(--color-fill-2);
  color: var(--color-text-3);
}

.icon-primary {
  background: rgb(var(--arcoblue-1));
  color: rgb(var(--arcoblue-6));
}

.icon-success {
  background: rgb(var(--green-1));
  color: rgb(var(--green-6));
}

.icon-warning {
  background: rgb(var(--orange-1));
  color: rgb(var(--orange-6));
}

.stat-label {
  font-size: 12px;
  color: var(--color-text-3);
  margin-bottom: 4px;
}

.stat-value {
  font-size: 28px;
  line-height: 36px;
  font-weight: 700;
  color: var(--color-text-1);
  font-variant-numeric: tabular-nums;
}

.value-primary { color: rgb(var(--arcoblue-6)); }
.value-success { color: rgb(var(--green-6)); }
.value-warning { color: rgb(var(--orange-6)); }
</style>
