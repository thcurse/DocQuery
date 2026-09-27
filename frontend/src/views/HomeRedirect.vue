<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
const auth = useAuth()
const router = useRouter()
onMounted(() => {
  void router.replace(auth.admin?.role === '1' ? '/tenants' : '/knowledge-bases')
})
</script>
<template><div role="status" class="state">正在进入工作空间…</div></template>
