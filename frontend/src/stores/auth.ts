import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface AuthUser {
  id: number
  username: string
  role: 'USER' | 'ADMIN'
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const accessToken = ref<string | null>(localStorage.getItem('accessToken'))

  const isAuthenticated = computed(() => accessToken.value !== null)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  function setAuth(token: string, userData: AuthUser): void {
    accessToken.value = token
    user.value = userData
    localStorage.setItem('accessToken', token)
  }

  function clearAuth(): void {
    accessToken.value = null
    user.value = null
    localStorage.removeItem('accessToken')
  }

  return { user, accessToken, isAuthenticated, isAdmin, setAuth, clearAuth }
})
