import { useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { asset } from '../utils/asset'
import './Login.css'

export default function Login() {
  const { login, register, user, notice } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [mode, setMode] = useState('login')
  const [form, setForm] = useState({ username: '', password: '', displayName: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const from = location.state?.from?.pathname || '/'

  if (user) {
    return <Navigate to={from} replace />
  }

  const submit = async (e) => {
    e.preventDefault()
    if (loading) return
    setError('')
    setLoading(true)
    try {
      if (mode === 'login') {
        await login(form.username.trim(), form.password)
      } else {
        await register(form.username.trim(), form.password, form.displayName.trim())
      }
      navigate(from, { replace: true })
    } catch (err) {
      setError(err.message || '操作失败，请重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <img src={asset('logo.png')} alt="sspOffer" />
          <div>
            <h1>sspOffer 面经助手</h1>
            <p>登录后进入你自己的面试空间</p>
          </div>
        </div>

        <div className="login-tabs">
          <button
            type="button"
            className={mode === 'login' ? 'active' : ''}
            onClick={() => { setMode('login'); setError('') }}
          >
            登录
          </button>
          <button
            type="button"
            className={mode === 'register' ? 'active' : ''}
            onClick={() => { setMode('register'); setError('') }}
          >
            注册
          </button>
        </div>

        {notice && !error && <div className="login-notice">{notice}</div>}
        {error && <div className="login-error">{error}</div>}

        <form onSubmit={submit}>
          <label>
            用户名
            <input
              value={form.username}
              onChange={e => setForm({ ...form, username: e.target.value })}
              placeholder="3-32 位字母、数字、下划线或中文"
              autoComplete="username"
              required
            />
          </label>

          {mode === 'register' && (
            <label>
              显示名称（可选）
              <input
                value={form.displayName}
                onChange={e => setForm({ ...form, displayName: e.target.value })}
                placeholder="展示在右上角，默认与用户名一致"
              />
            </label>
          )}

          <label>
            密码
            <input
              type="password"
              value={form.password}
              onChange={e => setForm({ ...form, password: e.target.value })}
              placeholder="至少 6 位"
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              required
            />
          </label>

          <button type="submit" className="login-submit" disabled={loading}>
            {loading ? '请稍候...' : mode === 'login' ? '登录' : '创建空间并登录'}
          </button>
        </form>

        <p className="login-hint">
          {mode === 'register'
            ? '注册会自动创建一个独立空间，面经、简历、面试记录仅自己可见。'
            : '面经库为所有账号共享，个人数据按空间隔离。'}
        </p>
      </div>
    </div>
  )
}
