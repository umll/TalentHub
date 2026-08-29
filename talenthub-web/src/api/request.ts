import axios, { type AxiosResponse } from 'axios'
import { Message } from '@arco-design/web-vue'
import { useUserStore } from '@/stores/user'

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

const http = axios.create({ baseURL: '/api', timeout: 10000 })

http.interceptors.request.use((config) => {
  config.headers['X-User-Id'] = String(useUserStore().current.id)
  return config
})

// 统一解包 Result<T> 并弹错误提示：页面代码只拿 data、只关心成功分支
http.interceptors.response.use(
  (response) => {
    const result = response.data as ApiResult<unknown>
    if (result.code !== 0) {
      Message.error(result.message)
      return Promise.reject(result)
    }
    return result.data as unknown as AxiosResponse
  },
  (error) => {
    Message.error('网络异常，请稍后重试')
    return Promise.reject(error)
  }
)

export function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return http.get(url, { params }) as Promise<T>
}

export function post<T>(url: string, data?: unknown): Promise<T> {
  return http.post(url, data) as Promise<T>
}

export function put<T>(url: string, data?: unknown): Promise<T> {
  return http.put(url, data) as Promise<T>
}

export function del<T>(url: string): Promise<T> {
  return http.delete(url) as Promise<T>
}
