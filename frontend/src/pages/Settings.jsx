import { useEffect, useState } from 'react'
import { api } from '../api/client'
import './Settings.css'

const PROVIDER_LABELS = {
  deepseek: 'DeepSeek',
  zhipu: '智谱 GLM',
  custom: '自定义（OpenAI 兼容）'
}

export default function Settings() {
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [info, setInfo] = useState(null)
  const [form, setForm] = useState({
    provider: 'deepseek',
    baseUrl: 'https://api.deepseek.com/v1',
    apiKey: '',
    chatModel: 'deepseek-chat',
    planModel: 'deepseek-chat'
  })
  const [message, setMessage] = useState(null) // {ok, text}

  const applyInfo = (data, keepKey = true) => {
    setInfo(data)
    setForm(prev => ({
      provider: data.provider || prev.provider,
      baseUrl: data.baseUrl || prev.baseUrl,
      apiKey: keepKey ? '' : prev.apiKey,
      chatModel: data.chatModel || prev.chatModel,
      planModel: data.planModel || prev.planModel
    }))
  }

  useEffect(() => {
    api.getLlmSettings()
      .then(data => applyInfo(data))
      .catch(e => setMessage({ ok: false, text: e.message }))
      .finally(() => setLoading(false))
  }, [])

  const handleProvider = (provider) => {
    const preset = info?.presets?.[provider] || {}
    setForm(prev => ({
      ...prev,
      provider,
      baseUrl: preset.baseUrl || (provider === 'custom' ? '' : prev.baseUrl),
      chatModel: preset.chatModel || '',
      planModel: preset.planModel || preset.chatModel || ''
    }))
  }

  const payload = () => {
    const body = {
      provider: form.provider,
      baseUrl: form.baseUrl.trim(),
      chatModel: form.chatModel.trim(),
      planModel: form.planModel.trim()
    }
    if (form.apiKey.trim()) body.apiKey = form.apiKey.trim()
    return body
  }

  const save = async (e) => {
    e.preventDefault()
    setSaving(true)
    setMessage(null)
    try {
      const data = await api.saveLlmSettings(payload())
      applyInfo(data)
      setMessage({ ok: true, text: '已保存，新的模型配置立即生效' })
    } catch (err) {
      setMessage({ ok: false, text: err.message })
    } finally {
      setSaving(false)
    }
  }

  const test = async () => {
    setTesting(true)
    setMessage(null)
    try {
      const data = await api.testLlmSettings(payload())
      setMessage({ ok: !!data.ok, text: data.ok ? (data.message || '连接成功') : (data.error || '连接失败') })
    } catch (err) {
      setMessage({ ok: false, text: err.message })
    } finally {
      setTesting(false)
    }
  }

  const reset = async () => {
    if (!confirm('恢复为系统默认模型配置？会删除你填写的 Key。')) return
    try {
      const data = await api.resetLlmSettings()
      applyInfo(data, false)
      setMessage({ ok: true, text: '已恢复系统默认' })
    } catch (err) {
      setMessage({ ok: false, text: err.message })
    }
  }

  if (loading) {
    return <div className="settings-page"><p className="settings-hint">加载中...</p></div>
  }

  return (
    <div className="settings-page">
      <div className="settings-card">
        <h2>大模型设置</h2>
        <p className="settings-hint">
          支持 DeepSeek 与智谱（都兼容 OpenAI 接口）。填自己的 Key 后，面试出题、追问、报告都会用你的模型；
          不填则使用部署方配置的默认模型。
        </p>

        <div className="settings-status">
          <span className={`settings-dot ${info?.usingSystemDefault ? 'system' : 'own'}`} />
          {info?.usingSystemDefault ? '当前使用系统默认模型' : '当前使用你填写的模型'}
          {info?.configured
            ? <span className="settings-effective">{info.effectiveProvider} / {info.effectiveModel}</span>
            : <span className="settings-effective warn">尚未配置 Key，AI 功能不可用</span>}
          {info?.hasApiKey && <span className="settings-key">Key: {info.apiKeyMasked}</span>}
        </div>

        {message && (
          <div className={message.ok ? 'settings-success' : 'settings-error'}>{message.text}</div>
        )}

        <form onSubmit={save} className="settings-form">
          <label>
            模型供应商
            <select value={form.provider} onChange={e => handleProvider(e.target.value)}>
              {Object.entries(PROVIDER_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>

          <label>
            Base URL
            <input
              value={form.baseUrl}
              onChange={e => setForm({ ...form, baseUrl: e.target.value })}
              placeholder="https://api.deepseek.com/v1"
              required
            />
            <span className="settings-field-hint">DeepSeek 填 https://api.deepseek.com/v1，智谱填 https://open.bigmodel.cn/api/paas/v4</span>
          </label>

          <label>
            API Key
            <input
              type="password"
              value={form.apiKey}
              onChange={e => setForm({ ...form, apiKey: e.target.value })}
              placeholder={info?.hasApiKey ? `已保存（${info.apiKeyMasked}），留空保持不变` : 'sk-...'}
              autoComplete="off"
            />
          </label>

          <div className="settings-row">
            <label>
              对话模型
              <input
                value={form.chatModel}
                onChange={e => setForm({ ...form, chatModel: e.target.value })}
                placeholder="deepseek-chat"
                required
              />
            </label>
            <label>
              计划/报告模型
              <input
                value={form.planModel}
                onChange={e => setForm({ ...form, planModel: e.target.value })}
                placeholder="deepseek-chat"
                required
              />
            </label>
          </div>

          <div className="settings-actions">
            <button type="submit" className="settings-primary" disabled={saving}>
              {saving ? '保存中...' : '保存'}
            </button>
            <button type="button" className="settings-secondary" onClick={test} disabled={testing}>
              {testing ? '测试中...' : '测试连接'}
            </button>
            <button type="button" className="settings-link" onClick={reset}>恢复系统默认</button>
          </div>
        </form>
      </div>
    </div>
  )
}
