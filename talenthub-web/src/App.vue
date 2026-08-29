<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import UserSwitcher from '@/components/UserSwitcher.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const selectedKeys = computed(() => {
  if (route.path.startsWith('/admin')) return ['/admin']
  if (route.path.startsWith('/my')) return ['/my']
  return ['/']
})

function onMenuClick(key: string) {
  router.push(key)
}
</script>

<template>
  <a-layout class="app-layout">
    <a-layout-header class="app-header">
      <div class="app-title">TalentHub 抢课演示</div>
      <a-menu
        mode="horizontal"
        :selected-keys="selectedKeys"
        class="app-menu"
        @menu-item-click="onMenuClick"
      >
        <a-menu-item key="/">课程列表</a-menu-item>
        <a-menu-item key="/my">我的报名</a-menu-item>
        <a-menu-item v-if="userStore.current.admin" key="/admin">管理端</a-menu-item>
      </a-menu>
      <UserSwitcher />
    </a-layout-header>
    <a-layout-content class="app-content">
      <router-view />
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
  background-color: var(--color-fill-2);
}

.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 24px;
  background-color: var(--color-bg-2);
  border-bottom: 1px solid var(--color-border-2);
}

.app-title {
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
}

.app-menu {
  flex: 1;
}

.app-content {
  max-width: 1080px;
  width: 100%;
  margin: 0 auto;
  padding: 24px;
}
</style>
