<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DEMO_USERS, useUserStore } from '@/stores/user'

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()

const currentId = computed({
  get: () => userStore.current.id,
  set: (id: number) => {
    userStore.switchTo(id)
    if (!userStore.current.admin && route.path.startsWith('/admin')) {
      router.push('/')
    }
  }
})
</script>

<template>
  <a-select v-model="currentId" class="user-switcher" size="small">
    <a-option v-for="user in DEMO_USERS" :key="user.id" :value="user.id">
      {{ user.name }}{{ user.admin ? '（管理员）' : '' }}
    </a-option>
  </a-select>
</template>

<style scoped>
.user-switcher {
  width: 160px;
}
</style>
