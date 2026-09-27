import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from '@tanstack/vue-query'
import App from './App.vue'
import { router } from './router'
import { queryClient } from './queryClient'
import { useAuth } from './stores/auth'
import { setUnauthorizedHandler } from './api/client'
import './styles.css'
const app = createApp(App)
app.use(createPinia()).use(VueQueryPlugin, { queryClient }).use(router)
setUnauthorizedHandler(() => {
  useAuth().clear()
  void router.replace('/login')
})
app.mount('#app')
