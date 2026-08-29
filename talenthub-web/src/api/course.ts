import { get } from '@/api/request'
import type { Course } from '@/types/course'

export function listCourses(): Promise<Course[]> {
  return get('/courses')
}

export function getCourse(id: number): Promise<Course> {
  return get(`/courses/${id}`)
}
