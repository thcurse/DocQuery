import { defineStore } from 'pinia'
import { ref } from 'vue'
export const useUi = defineStore('ui', () => ({ collapsed: ref(false), mobileMenu: ref(false) }))
