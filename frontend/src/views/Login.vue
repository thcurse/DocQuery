<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { NInput, NButton, NAlert } from 'naive-ui'
import { useAuth } from '../stores/auth'
import { errorMessage } from '../composables/common'
const loginName = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const auth = useAuth()
const router = useRouter()
const route = useRoute()
async function login() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await auth.login(loginName.value, password.value)
    password.value = ''
    await router.replace('/')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main class="login-page">
    <section class="login-story">
      <a class="brand" href="#/"><span class="brand-mark">D</span> DocQuery</a>
      <div>
        <span class="eyebrow">KNOWLEDGE, CONNECTED.</span>
        <h1>让每一份文档<br />成为可靠的答案。</h1>
        <p>集中管理知识，让应用找到答案，<br />让每一次回答都有据可循。</p>
        <div class="story-document">
          <span class="book-mark">≡</span><strong>从文档到知识</strong>
          <p>上传 · 解析 · 检索 · 引用</p>
          <div class="story-line"></div>
          <div class="story-line short"></div>
        </div>
      </div>
      <small>DocQuery · 企业知识服务</small>
    </section>
    <section class="login-form">
      <div class="login-box">
        <span class="eyebrow">WELCOME BACK</span>
        <h2>登录管理后台</h2>
        <p class="muted">管理员专用入口</p>
        <NAlert v-if="error || route.query.restore" type="error">{{
          error || '暂时无法恢复会话，请重试登录'
        }}</NAlert>
        <form @submit.prevent="login">
          <label class="field"
            >管理员账号<NInput
              v-model:value="loginName"
              :input-props="{
                required: true,
                'aria-label': '管理员账号',
                autocomplete: 'username',
              }"
              placeholder="输入管理员账号"
              size="large" /></label
          ><label class="field"
            >密码<NInput
              v-model:value="password"
              type="password"
              show-password-on="click"
              :input-props="{
                required: true,
                'aria-label': '密码',
                autocomplete: 'current-password',
              }"
              placeholder="输入密码"
              size="large" /></label
          ><NButton block type="primary" size="large" attr-type="submit" :loading="busy"
            >登录</NButton
          >
        </form>
        <p class="login-foot">账号由平台管理员分配。如需帮助，请联系管理员。</p>
      </div>
    </section>
  </main>
</template>
