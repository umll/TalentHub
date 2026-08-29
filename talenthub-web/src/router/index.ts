import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'course-list', component: () => import('@/views/course/CourseListView.vue') },
    { path: '/courses/:id', name: 'course-detail', component: () => import('@/views/course/CourseDetailView.vue') },
    { path: '/my', name: 'my-enrollment', component: () => import('@/views/enrollment/MyEnrollmentView.vue') },
    { path: '/admin', name: 'admin-course', component: () => import('@/views/admin/AdminCourseView.vue') }
  ]
})

export default router
