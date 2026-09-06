import { createRouter, createWebHistory } from 'vue-router'
import { hasSession, onSessionClear } from './lib/session'
import LoginPage from './pages/LoginPage.vue'
import TasksPage from './pages/TasksPage.vue'
import TaskPage from './pages/TaskPage.vue'
import TracePage from './pages/TracePage.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginPage },
    { path: '/tasks', component: TasksPage },
    { path: '/agents/:agentId/run', component: TasksPage },
    { path: '/tasks/:taskId/trace', component: TracePage },
    { path: '/tasks/:taskId', component: TaskPage },
    { path: '/:pathMatch(.*)*', redirect: '/tasks' },
  ],
})

router.beforeEach(to => {
  if (to.path !== '/login' && !hasSession()) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && hasSession()) return '/tasks'
})
onSessionClear(() => { void router.replace('/login') })
