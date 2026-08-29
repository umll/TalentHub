/** 与后端 EnrollResultVO / EnrollmentVO 对齐 */

export interface EnrollResult {
  alreadyEnrolled: boolean
}

export interface Enrollment {
  id: number
  courseId: number
  courseTitle: string
  status: number
  enrolledAt: string
  canceledAt: string | null
  courseStatus: number
  enrollEnd: string
}

export const ENROLLMENT_STATUS = {
  ENROLLED: 1,
  CANCELED: 2
} as const
