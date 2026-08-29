import { get, post, put } from '@/api/request'
import type { CourseForm, ReconcileLog } from '@/types/course'

export function createCourse(form: CourseForm): Promise<number> {
  return post('/admin/courses', form)
}

export function updateCourse(id: number, form: CourseForm): Promise<void> {
  return put(`/admin/courses/${id}`, form)
}

export function preheatCourse(id: number): Promise<void> {
  return post(`/admin/courses/${id}/preheat`)
}

export function listReconcileLogs(): Promise<ReconcileLog[]> {
  return get('/admin/reconcile-logs')
}
