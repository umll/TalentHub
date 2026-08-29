/** 与后端 CourseVO / CourseSaveDTO / ReconcileLogVO 对齐 */

export interface Course {
  id: number
  title: string
  totalQuota: number
  stock: number
  enrollStart: string
  enrollEnd: string
  status: number
  enrolled: boolean
}

export const COURSE_STATUS = {
  NOT_STARTED: 0,
  OPEN: 1,
  ENDED: 2,
  CANCELED: 3
} as const

export interface CourseForm {
  title: string
  totalQuota: number
  enrollStart: string
  enrollEnd: string
}

export interface ReconcileLog {
  id: number
  courseId: number
  courseTitle: string
  redisStock: number | null
  dbStock: number
  action: string
  createdAt: string
}
