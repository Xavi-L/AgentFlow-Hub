<script setup lang="ts">
import { session, clearSession } from './lib/session'
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink to="/tasks" class="brand"><span class="brand-symbol">A</span> AgentFlow <span class="brand-hub">HUB</span></RouterLink>
      <nav v-if="session.token" aria-label="主导航">
        <RouterLink to="/tasks">任务</RouterLink>
        <RouterLink to="/agents">Agent</RouterLink>
        <RouterLink to="/knowledge-bases">知识库</RouterLink>
        <span class="account">{{ session.user?.displayName || session.user?.username }}</span>
        <button class="text-button" @click="clearSession()">退出登录</button>
      </nav>
      <span v-else class="muted">任务执行与 Trace</span>
    </header>
    <main><RouterView :key="$route.fullPath" /></main>
  </div>
</template>
