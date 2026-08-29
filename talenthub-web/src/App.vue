<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import UserSwitcher from '@/components/UserSwitcher.vue'

const route = useRoute()
const userStore = useUserStore()

const navs = [
  { path: '/', label: '课程列表', adminOnly: false },
  { path: '/my', label: '我的报名', adminOnly: false },
  { path: '/admin', label: '管理端', adminOnly: true }
]

function isActive(path: string): boolean {
  if (path === '/') {
    return route.path === '/' || route.path.startsWith('/courses')
  }
  return route.path.startsWith(path)
}
</script>

<template>
  <a-layout class="app-layout">
    <a-layout-header class="app-header">
      <div class="app-header-inner">
        <router-link to="/" class="app-brand">TalentHub 抢课演示</router-link>
        <nav class="app-nav">
          <template v-for="nav in navs" :key="nav.path">
            <router-link
              v-if="!nav.adminOnly || userStore.current.admin"
              :to="nav.path"
              class="app-nav-link"
              :class="{ active: isActive(nav.path) }"
            >
              {{ nav.label }}
            </router-link>
          </template>
        </nav>
        <div class="app-header-right">
          <span v-if="userStore.current.admin" class="status-pill warning">Admin</span>
          <UserSwitcher />
        </div>
      </div>
    </a-layout-header>
    <a-layout-content class="app-content">
      <router-view />
    </a-layout-content>
    <a-layout-footer class="app-footer">
      <span class="footer-brand">TalentHub</span>
      <span>内部培训抢课演示系统 · Redis 预扣 + PostgreSQL 防超卖</span>
    </a-layout-footer>
  </a-layout>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
}

.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: 64px;
  background: var(--color-bg-2);
  border-bottom: 1px solid var(--color-border-2);
  box-shadow: 0 2px 8px rgba(29, 33, 41, 0.04);
}

.app-header-inner {
  max-width: 1200px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 0 24px;
}

.app-brand {
  font-size: 18px;
  font-weight: 700;
  color: rgb(var(--arcoblue-6));
  text-decoration: none;
  white-space: nowrap;
}

.app-nav {
  display: flex;
  gap: 24px;
  flex: 1;
  height: 100%;
  align-items: stretch;
}

.app-nav-link {
  color: var(--color-text-2);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  border-bottom: 2px solid transparent;
  transition: color 0.2s ease;
}

.app-nav-link:hover {
  color: rgb(var(--arcoblue-6));
}

.app-nav-link.active {
  color: rgb(var(--arcoblue-6));
  font-weight: 700;
  border-bottom-color: rgb(var(--arcoblue-6));
}

.app-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.app-content {
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
  padding: 24px 24px 48px;
}

.app-footer {
  border-top: 1px solid var(--color-border-2);
  background: var(--color-bg-2);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  color: var(--color-text-3);
  font-size: 12px;
}

.footer-brand {
  font-weight: 600;
  color: var(--color-text-1);
}
</style>
