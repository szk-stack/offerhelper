import { useState } from 'react'
import { useAuth } from '../context/AuthContext'
import './Account.css'

export default function Account() {
  const { user, changePassword } = useAuth()
  const [form, setForm] = useState({ oldPassword: '', newPassword: '', confirm: '' })
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const submit = async (e) => {
    e.preventDefault()
    setMessage('')
    setError('')
    if (form.newPassword.length < 6) {
      setError('新密码至少 6 位')
      return
    }
    if (form.newPassword !== form.confirm) {
      setError('两次输入的新密码不一致')
      return
    }
    setLoading(true)
    try {
      await changePassword(form.oldPassword, form.newPassword)
      setForm({ oldPassword: '', newPassword: '', confirm: '' })
      setMessage('密码已修改，其他设备上的登录已失效')
    } catch (err) {
      setError(err.message || '修改失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="account-page">
      <div className="account-card">
        <h2>账号</h2>
        <div className="account-meta">
          <div><span>用户名</span><strong>{user?.username}</strong></div>
          <div><span>显示名称</span><strong>{user?.displayName || user?.username}</strong></div>
          <div><span>空间</span><strong>{user?.tenantName || '-'}</strong></div>
        </div>
      </div>

      <div className="account-card">
        <h3>修改密码</h3>
        <p className="account-hint">修改后其它设备需要重新登录；当前设备会自动续期。</p>
        {error && <div className="account-error">{error}</div>}
        {message && <div className="account-success">{message}</div>}
        <form onSubmit={submit}>
          <label>
            当前密码
            <input
              type="password"
              value={form.oldPassword}
              onChange={e => setForm({ ...form, oldPassword: e.target.value })}
              autoComplete="current-password"
              required
            />
          </label>
          <label>
            新密码
            <input
              type="password"
              value={form.newPassword}
              onChange={e => setForm({ ...form, newPassword: e.target.value })}
              autoComplete="new-password"
              required
            />
          </label>
          <label>
            确认新密码
            <input
              type="password"
              value={form.confirm}
              onChange={e => setForm({ ...form, confirm: e.target.value })}
              autoComplete="new-password"
              required
            />
          </label>
          <button type="submit" className="account-submit" disabled={loading}>
            {loading ? '提交中...' : '保存'}
          </button>
        </form>
      </div>
    </div>
  )
}
