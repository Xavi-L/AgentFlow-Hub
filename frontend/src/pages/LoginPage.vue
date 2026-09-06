<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../lib/api'
import { message } from '../presentation'

const route = useRoute(), router = useRouter()
const username = ref(''), password = ref(''), busy = ref(false), error = ref('')
async function login() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    await api.login(username.value, password.value)
    password.value = ''
    const target = typeof route.query.redirect === 'string' && /^\/(tasks|agents)(\/|$)/.test(route.query.redirect) ? route.query.redirect : '/tasks'
    await router.replace(target)
  } catch (e) { error.value = message(e) } finally { busy.value = false }
}
</script>

<template>
  <div class="login-layout">
    <div class="login-intro"><div class="eyebrow">AGENTFLOW HUB</div><h1>从任务到答案，<br />过程有据可查。</h1><p>运行 Agent，查看持久执行事件，<br />在 Trace 中回看每一步。</p><div class="flow-line"><span>提交任务</span><i>→</i><span>执行与恢复</span><i>→</i><span>答案与引用</span></div></div>
    <section class="panel login-card"><h2>登录</h2><p class="muted">使用已有账号继续。</p>
      <form @submit.prevent="login">
        <label for="username">用户名</label><input id="username" v-model="username" autocomplete="username" required :disabled="busy" />
        <label for="password">密码</label><input id="password" v-model="password" type="password" autocomplete="current-password" required :disabled="busy" />
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button class="primary wide" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
      </form>
    </section>
  </div>
</template>
