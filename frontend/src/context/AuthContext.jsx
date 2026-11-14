import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { api } from '../api/client'
import { AUTH_EVENT, clearToken, getToken, setToken } from '../utils/authToken'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState('')

  const refresh = useCallback(async () => {
    if (!getToken()) {
      setUser(null)
      setLoading(false)
      return null
    }
    try {
      const me = await api.me()
      setUser(me)
      return me
    } catch (e) {
      clearToken()
      setUser(null)
      return null
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  useEffect(() => {
    const onChanged = () => {
      if (!getToken()) {
        setUser(null)
        setNotice('登录已过期，请重新登录')
      }
    }
    window.addEventListener(AUTH_EVENT, onChanged)
    return () => window.removeEventListener(AUTH_EVENT, onChanged)
  }, [])

  const login = async (username, password) => {
    const res = await api.login(username, password)
    setToken(res.token)
    setUser(res.user)
    setNotice('')
    return res.user
  }

  const register = async (username, password, displayName) => {
    const res = await api.register(username, password, displayName)
    setToken(res.token)
    setUser(res.user)
    setNotice('')
    return res.user
  }

  const logout = async () => {
    try {
      await api.logout()
    } catch (e) {
      // 令牌可能已过期，忽略
    }
    clearToken()
    setUser(null)
    setNotice('')
  }

  const changePassword = async (oldPassword, newPassword) => {
    const res = await api.changePassword(oldPassword, newPassword)
    if (res && res.token) {
      setToken(res.token)
    }
    return res
  }

  return (
    <AuthContext.Provider value={{ user, loading, notice, login, register, logout, refresh, changePassword }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
