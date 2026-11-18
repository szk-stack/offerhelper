import { useEffect, useState } from 'react'
import { api } from '../api/client'
import './Settings.css'

const PROVIDER_LABELS = {
  deepseek: 'DeepSeek',
  zhipu: '智谱 GLM',
  custom: '自定义（OpenAI 兼容）'
}

const ASR_LABELS = {
  siliconflow: '硅基流动 SenseVoice（免费）',
  dashscope: '阿里百炼 Qwen3-ASR',
  tencent: '腾讯云一句话识别',
  custom: '自定义（OpenAI 兼容）'
}

/** 面试里高频出现的术语，默认填好，用户可自行增删 */
const DEFAULT_HOTWORDS = [
  'Java', 'Spring Boot', 'Spring Cloud', 'JVM', 'GC', 'MySQL', 'Redis', 'Kafka',
  'RabbitMQ', 'Elasticsearch', 'Docker', 'Kubernetes', 'Nginx', '微服务', '分布式锁',
  '分布式事务', '消息队列', '索引下推', '慢查询', '读写分离', '分库分表', '幂等',
  '限流', '熔断', '降级', '缓存穿透', '缓存击穿', '缓存雪崩', 'CAP 定理', '一致性哈希',
  '线程池', '并发编程', '乐观锁', '悲观锁', '事务隔离级别', 'MVCC', 'B+ 树', '红黑树',
  '哈希表', '动态规划', '时间复杂度', '空间复杂度', '压力测试', '链路追踪'
].join(',')

/** 根据 Base URL 形态给出「程序会怎么调」的提示，避免拼错路径还不知道 */
function asrEndpointHint(baseUrl) {
  const url = (baseUrl || '').toLowerCase()
  if (url.includes('/multimodal-generation/generation')) {
    return '已识别为百炼 DashScope 端点：会直接调这个地址，按 multimodal-generation 协议传音频。'
  }
  if (url.endsWith('/audio/transcriptions')) {
    return '已识别为完整端点：会直接调这个地址，不再拼接路径。'
  }
  if (url.includes('dashscope.aliyuncs.com') || url.includes('maas.')) {
    return '会补成 {Base}/services/aigc/multimodal-generation/generation'
  }
  return '会补成 {Base}/audio/transcriptions。填到 /v1 即可，不要填到 /audio/transcriptions 以外的方法名。'
}

export default function Settings() {
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [info, setInfo] = useState(null)
  const [form, setForm] = useState({
    provider: 'deepseek',
    baseUrl: 'https://api.deepseek.com',
    apiKey: '',
    chatModel: 'deepseek-flash',
    planModel: 'deepseek-flash',
    visionModel: 'deepseek-flash'
  })
  const [message, setMessage] = useState(null) // {ok, text}

  // ── 语音识别 ──
  const [asrInfo, setAsrInfo] = useState(null)
  const [asrSaving, setAsrSaving] = useState(false)
  const [asrTesting, setAsrTesting] = useState(false)
  const [asrMessage, setAsrMessage] = useState(null)
  const [asrForm, setAsrForm] = useState({
    provider: 'siliconflow',
    baseUrl: 'https://api.siliconflow.cn/v1',
    apiKey: '',
    model: 'FunAudioLLM/SenseVoiceSmall',
    hotwords: DEFAULT_HOTWORDS
  })

  const applyInfo = (data, keepKey = true) => {
    setInfo(data)
    setForm(prev => ({
      provider: data.provider || prev.provider,
      baseUrl: data.baseUrl || prev.baseUrl,
      apiKey: keepKey ? '' : prev.apiKey,
      chatModel: data.chatModel || prev.chatModel,
      planModel: data.planModel || prev.planModel,
      visionModel: data.visionModel || prev.visionModel
    }))
  }

  const applyAsrInfo = (data) => {
    setAsrInfo(data)
    setAsrForm(prev => ({
      provider: data.provider || prev.provider,
      baseUrl: data.baseUrl || prev.baseUrl,
      apiKey: '',
      model: data.model || prev.model,
      hotwords: data.hotwords || ''
    }))
  }

  useEffect(() => {
    api.getLlmSettings()
      .then(data => applyInfo(data))
      .catch(e => setMessage({ ok: false, text: e.message }))
      .finally(() => setLoading(false))
    api.getAsrSettings()
      .then(data => applyAsrInfo(data))
      .catch(e => setAsrMessage({ ok: false, text: e.message }))
  }, [])

  const handleProvider = (provider) => {
    const preset = info?.presets?.[provider] || {}
    setForm(prev => ({
      ...prev,
      provider,
      baseUrl: preset.baseUrl || (provider === 'custom' ? '' : prev.baseUrl),
      chatModel: preset.chatModel || '',
      planModel: preset.planModel || preset.chatModel || '',
      visionModel: preset.visionModel || preset.chatModel || ''
    }))
  }

  const handleAsrProvider = (provider) => {
    const preset = asrInfo?.presets?.[provider] || {}
    setAsrForm(prev => ({
      ...prev,
      provider,
      baseUrl: preset.baseUrl || (provider === 'custom' ? '' : prev.baseUrl),
      model: preset.model || ''
    }))
  }

  const payload = () => {
    const body = {
      provider: form.provider,
      baseUrl: form.baseUrl.trim(),
      chatModel: form.chatModel.trim(),
      planModel: form.planModel.trim(),
      visionModel: form.visionModel.trim()
    }
    if (form.apiKey.trim()) body.apiKey = form.apiKey.trim()
    return body
  }

  const asrPayload = () => {
    const body = {
      provider: asrForm.provider,
      baseUrl: asrForm.baseUrl.trim(),
      model: asrForm.model.trim(),
      hotwords: asrForm.hotwords.trim()
    }
    if (asrForm.apiKey.trim()) body.apiKey = asrForm.apiKey.trim()
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

  const saveAsr = async (e) => {
    e.preventDefault()
    setAsrSaving(true)
    setAsrMessage(null)
    try {
      const data = await api.saveAsrSettings(asrPayload())
      applyAsrInfo(data)
      setAsrMessage({ ok: true, text: '已保存，下次开始面试时生效' })
    } catch (err) {
      setAsrMessage({ ok: false, text: err.message })
    } finally {
      setAsrSaving(false)
    }
  }

  const testAsr = async () => {
    setAsrTesting(true)
    setAsrMessage(null)
    try {
      const data = await api.testAsrSettings(asrPayload())
      setAsrMessage({ ok: !!data.ok, text: data.ok ? (data.message || '连接成功') : (data.error || '连接失败') })
    } catch (err) {
      setAsrMessage({ ok: false, text: err.message })
    } finally {
      setAsrTesting(false)
    }
  }

  const resetAsr = async () => {
    if (!confirm('恢复为系统默认的语音识别配置？会删除你填写的 Key。')) return
    try {
      const data = await api.resetAsrSettings()
      applyAsrInfo(data)
      setAsrMessage({ ok: true, text: '已恢复系统默认' })
    } catch (err) {
      setAsrMessage({ ok: false, text: err.message })
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
          支持 DeepSeek（deepseek-flash，原生支持图片）与智谱（OpenAI 兼容接口）。填自己的 Key 后，
          面试出题、追问、报告、图片解析都会用你的模型；不填则使用部署方配置的默认模型。
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
              placeholder="https://api.deepseek.com"
              required
            />
            <span className="settings-field-hint">DeepSeek 填 https://api.deepseek.com（也兼容 /v1），智谱填 https://open.bigmodel.cn/api/paas/v4</span>
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
                placeholder="deepseek-flash"
                required
              />
            </label>
            <label>
              计划/报告模型
              <input
                value={form.planModel}
                onChange={e => setForm({ ...form, planModel: e.target.value })}
                placeholder="deepseek-flash"
                required
              />
            </label>
          </div>

          <label>
            图片模型（可选）
            <input
              value={form.visionModel}
              onChange={e => setForm({ ...form, visionModel: e.target.value })}
              placeholder="deepseek-flash"
            />
            <span className="settings-field-hint">
              用于面经截图、图片简历解析。DeepSeek 的 deepseek-flash 与智谱 glm-4v 系列都支持图片，留空则用对话模型。
            </span>
          </label>

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

      {/* ───────────── 语音识别 ───────────── */}
      <div className="settings-card">
        <h2>语音识别</h2>
        <p className="settings-hint">
          模拟面试里的「语音」按钮用它把回答转成文字。浏览器自带的识别在国内不稳定、
          技术术语也容易错，换成云端服务后准确率明显提升，还能挂热词表。
        </p>

        <div className="settings-status">
          <span className={`settings-dot ${asrInfo?.usingSystemDefault ? 'system' : 'own'}`} />
          {asrInfo?.usingSystemDefault ? '当前使用系统默认语音服务' : '当前使用你填写的语音服务'}
          {asrInfo?.configured
            ? <span className="settings-effective">{asrInfo.effectiveProvider} / {asrInfo.effectiveModel}</span>
            : <span className="settings-effective warn">尚未配置 Key，语音输入不可用</span>}
          {asrInfo?.hasApiKey && <span className="settings-key">Key: {asrInfo.apiKeyMasked}</span>}
        </div>

        {asrMessage && (
          <div className={asrMessage.ok ? 'settings-success' : 'settings-error'}>{asrMessage.text}</div>
        )}

        <form onSubmit={saveAsr} className="settings-form">
          <label>
            服务商
            <select value={asrForm.provider} onChange={e => handleAsrProvider(e.target.value)}>
              {Object.entries(ASR_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <span className="settings-field-hint">
              {asrForm.provider === 'siliconflow' && 'SenseVoiceSmall 目前免费，适合先试效果；有并发限速。'}
              {asrForm.provider === 'dashscope' && '每月 10 小时免费，中文识别强。Base URL 填到 /api/v1 即可；用专属工作空间的话填自己的域名。'}
              {asrForm.provider === 'tencent' && '每月 5000 次免费，热词表效果最好。Key 填「SecretId:SecretKey」，中间用英文冒号。'}
              {asrForm.provider === 'custom' && '任意 /audio/transcriptions 服务；如果填的是百炼的 multimodal-generation 端点，也会自动按百炼协议调用。'}
            </span>
          </label>

          <label>
            Base URL
            <input
              value={asrForm.baseUrl}
              onChange={e => setAsrForm({ ...asrForm, baseUrl: e.target.value })}
              placeholder="https://api.siliconflow.cn/v1"
              required
            />
            <span className="settings-field-hint">
              {asrEndpointHint(asrForm.baseUrl)}
            </span>
          </label>

          <label>
            {asrForm.provider === 'tencent' ? 'SecretId:SecretKey' : 'API Key'}
            <input
              type="password"
              value={asrForm.apiKey}
              onChange={e => setAsrForm({ ...asrForm, apiKey: e.target.value })}
              placeholder={asrInfo?.hasApiKey
                ? `已保存（${asrInfo.apiKeyMasked}），留空保持不变`
                : (asrInfo?.presets?.[asrForm.provider]?.keyHint || 'sk-...')}
              autoComplete="off"
            />
          </label>

          <label>
            识别模型
            <input
              value={asrForm.model}
              onChange={e => setAsrForm({ ...asrForm, model: e.target.value })}
              placeholder="FunAudioLLM/SenseVoiceSmall"
            />
            <span className="settings-field-hint">
              腾讯云常用值：16k_zh_large（大模型版，推荐）、16k_zh、bigmodel。
            </span>
          </label>

          <label>
            热词表
            <textarea
              value={asrForm.hotwords}
              onChange={e => setAsrForm({ ...asrForm, hotwords: e.target.value })}
              rows={3}
              placeholder="Java,Redis,Kafka,分布式锁,缓存穿透"
            />
            <span className="settings-field-hint">
              用逗号分隔，最多 128 个。腾讯云生效最明显；其他家会随请求一并下发，是否支持取决于服务商。
              已预填技术面试常见术语，可以按你的岗位增删。
            </span>
          </label>

          <div className="settings-actions">
            <button type="submit" className="settings-primary" disabled={asrSaving}>
              {asrSaving ? '保存中...' : '保存'}
            </button>
            <button type="button" className="settings-secondary" onClick={testAsr} disabled={asrTesting}>
              {asrTesting ? '测试中...' : '测试连接'}
            </button>
            <button type="button" className="settings-link" onClick={resetAsr}>恢复系统默认</button>
          </div>
        </form>
      </div>
    </div>
  )
}
