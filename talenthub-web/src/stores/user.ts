import { ref } from 'vue'
import { defineStore } from 'pinia'

export interface DemoUser {
  id: number
  name: string
  admin: boolean
}

/** 演示用户（登录简化，工程设计 §1.3）：无用户表，仅以 ID 标识 */
export const DEMO_USERS: DemoUser[] = [
  { id: 1, name: '管理员', admin: true },
  { id: 2, name: '张三', admin: false },
  { id: 3, name: '李四', admin: false },
  { id: 4, name: '王五', admin: false },
  { id: 5, name: '赵六', admin: false }
]

export const useUserStore = defineStore('user', () => {
  const current = ref<DemoUser>(DEMO_USERS[1])

  function switchTo(userId: number) {
    const user = DEMO_USERS.find((u) => u.id === userId)
    if (user) {
      current.value = user
    }
  }

  return { current, switchTo }
})
