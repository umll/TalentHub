import { del, get, post } from '@/api/request'
import type { Enrollment, EnrollResult } from '@/types/enrollment'

export function enroll(courseId: number): Promise<EnrollResult> {
  return post(`/courses/${courseId}/enroll`)
}

export function cancelEnroll(courseId: number): Promise<void> {
  return del(`/courses/${courseId}/enroll`)
}

export function listMyEnrollments(): Promise<Enrollment[]> {
  return get('/enrollments/my')
}
