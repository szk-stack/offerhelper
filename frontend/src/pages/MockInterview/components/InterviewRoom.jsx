import { useState, useRef, useEffect, useCallback } from 'react'
import { api, API_BASE } from '../../../api/client'
import { getToken } from '../../../utils/authToken'
import { useRecorder } from '../../../utils/useRecorder'

function parseSseBlock(block) {
  let event = 'message'
  const dataLines = []
  for (const line of block.split('\n')) {
    const clean = line.replace(/\r$/, '')
    if (clean.startsWith('event:')) event = clean.slice(6).trim()
    else if (clean.startsWith('data:')) dataLines.push(clean.slice(5).trim())
  }
  if (dataLines.length === 0) return null
  const raw = dataLines.join('\n')
  try {
    return { event, data: JSON.parse(raw) }
  } catch (e) {
    return { event, data: { text: raw } }
  }
}

export default function InterviewRoom({ session, onEnd, onPause, onUpdate }) {
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [pendingMessages, setPendingMessages] = useState([])
  const [streamText, setStreamText] = useState('')
  const [transcribing, setTranscribing] = useState(false)
  // { message, code, requestId }
  const [voiceError, setVoiceError] = useState(null)
  // 术语纠错默认关闭：勾上就每段自动校对，不勾可以点「校对术语」手动触发
  const [proofread, setProofread] = useState(false)
  const [proofreading, setProofreading] = useState(false)
  const scrollRef = useRef(null)

  // 录音期间输入框里的基础文本，每段识别结果往后追加
  const baseTextRef = useRef('')
  const inputRef = useRef('')
  useEffect(() => { inputRef.current = input }, [input])

  // 语音识别是否已配置：没配就别让用户白录一段再报错
  const [asrReady, setAsrReady] = useState(null)
  useEffect(() => {
    api.getAsrSettings()
      .then(d => setAsrReady(!!d?.configured))
      .catch(() => setAsrReady(false))
  }, [])

  /** 处理一段音频：上传 → 可选校对 → 追加到输入框 */
  const processSegment = useCallback(async (blob) => {
    // 记录代次：如果这段识别期间用户已经发送或清空了输入框，结果直接丢弃
    const gen = genRef.current
    try {
      const res = await api.transcribeAudio(blob)
      if (gen !== genRef.current) return
      if (res?.error) {
        setVoiceError({ message: res.error, code: res.code, requestId: res.requestId })
        return
      }
      let text = (res?.text || '').trim()
      if (!text) return

      if (proofread) {
        try {
          const fixed = await api.proofreadText(text)
          if (fixed?.text) text = fixed.text
        } catch (_) { /* 校对失败就用原始结果 */ }
      }
      if (gen !== genRef.current) return

      const prefix = baseTextRef.current
      const sep = prefix && !/[\s，。！？、；：]$/.test(prefix) ? '。' : ''
      const next = prefix ? `${prefix}${sep}${text}` : text
      baseTextRef.current = next
      setInput(next)
    } catch (e) {
      if (gen === genRef.current) setVoiceError({ message: e.message || '语音识别失败' })
    }
  }, [proofread])

  // 串行队列：连续说话时每段都会排队，避免后一段先返回把前一段覆盖掉
  const queueRef = useRef(Promise.resolve())
  const pendingRef = useRef(0)
  const genRef = useRef(0)

  const handleSegment = useCallback((blob) => {
    pendingRef.current += 1
    setTranscribing(true)
    queueRef.current = queueRef.current
      .then(() => processSegment(blob))
      .catch(() => {})
      .finally(() => {
        pendingRef.current = Math.max(0, pendingRef.current - 1)
        if (pendingRef.current === 0) setTranscribing(false)
      })
  }, [processSegment])

  const recorder = useRecorder({ onSegment: handleSegment })

  const toggleVoice = useCallback(() => {
    if (!recorder.active) {
      baseTextRef.current = inputRef.current
      setVoiceError(null)
    }
    recorder.toggle()
  }, [recorder])

  /** 手动触发一次术语校对，返回是否改动 */
  const runProofread = useCallback(async () => {
    const current = inputRef.current.trim()
    if (!current || proofreading) return
    setProofreading(true)
    try {
      const res = await api.proofreadText(current)
      if (res?.text && res.text !== current) {
        baseTextRef.current = res.text
        setInput(res.text)
      } else {
        setVoiceError({ message: '校对完成，没有发现需要修改的术语' })
        setTimeout(() => setVoiceError(null), 2500)
      }
    } catch (e) {
      setVoiceError({ message: e.message || '校对失败' })
    } finally {
      setProofreading(false)
    }
  }, [proofreading])

  // 合并后端对话 + 本地待确认消息
  const allMessages = [...(session.dialogues || []), ...pendingMessages]

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [allMessages, streamText])

  // 当后端数据刷新后（onUpdate 触发），清除本地待确认消息
  useEffect(() => {
    if (!loading && pendingMessages.length > 0) {
      setPendingMessages([])
    }
  }, [session.dialogues])

  const handleSend = async () => {
    // 录音中不发送：否则最后半句还没上屏就被清空了
    if (!input.trim() || loading || recorder.active) return

    // 丢弃还在路上的识别结果，避免发送后又把文字塞回输入框
    genRef.current += 1
    pendingRef.current = 0
    setTranscribing(false)

    const text = input
    setInput('')
    baseTextRef.current = ''

    // 立刻在本地显示用户消息
    setPendingMessages([{ role: 'candidate', content: text, pending: true }])
    setStreamText('')
    setLoading(true)
    
    try {
      await streamAnswer(text)
      await onUpdate()
    } catch (e) {
      alert('发送失败: ' + e.message)
      setInput(text)
      baseTextRef.current = text
      setPendingMessages([])
    } finally {
      setLoading(false)
      setStreamText('')
    }
  }

  /** 流式回答：边收边显示，结束后再刷新会话拿到落库的完整对话 */
  const streamAnswer = async (text) => {
    const token = getToken()
    const res = await fetch(`${API_BASE}/mock-interview/sessions/${session.sessionId}/answer/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ answer: text })
    })

    if (!res.ok) {
      let msg = `HTTP ${res.status}`
      try {
        const body = await res.json()
        msg = body.message || body.error || msg
      } catch (e) { }
      throw new Error(msg)
    }

    // 老浏览器不支持流式读取时退回同步接口
    if (!res.body || !res.body.getReader) {
      await api.answerMockInterview(session.sessionId, text)
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let full = ''

    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      let index
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, index)
        buffer = buffer.slice(index + 2)
        const evt = parseSseBlock(block)
        if (!evt) continue

        if (evt.event === 'chunk') {
          full += evt.data?.text || ''
          setStreamText(full)
        } else if (evt.event === 'done') {
          full = evt.data?.interviewerMessage || full
          setStreamText(full)
        } else if (evt.event === 'error') {
          throw new Error(evt.data?.message || '生成失败')
        }
      }
    }
  }

  // 计算进度
  let progressText = '准备中'
  let progressPercent = 0
  let currentNum = 0
  let totalNum = 0
  let phaseName = ''
  if (session.plan) {
    try {
      const plan = JSON.parse(session.plan.planJson)
      const phases = plan.phases || []
      // 计算总题数
      totalNum = phases.reduce((sum, p) => sum + (p.questions?.length || 0), 0)
      // 计算已完成题数
      for (let i = 0; i < session.currentPhaseIndex && i < phases.length; i++) {
        currentNum += phases[i].questions?.length || 0
      }
      currentNum += session.currentQuestionIndex
      // 当前阶段名
      const currentPhase = phases[session.currentPhaseIndex]
      phaseName = currentPhase?.title || ''
      progressPercent = totalNum > 0 ? Math.round((currentNum / totalNum) * 100) : 0
      progressText = `第 ${currentNum + 1}/${totalNum} 题`
    } catch (e) { }
  }

  return (
    <div className="room-container">
      <div className="room-header">
        <div className="room-header-top">
          <h3>正在面试: {session.company} - {session.department}</h3>
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button className="btn secondary" onClick={onPause} disabled={loading}>暂停</button>
            <button className="btn danger" onClick={onEnd} disabled={loading}>结束并出报告</button>
          </div>
        </div>
        {totalNum > 0 && (
          <div className="progress-section">
            <div className="progress-info">
              <span className="progress-count">{progressText}</span>
              <span className="progress-phase">{phaseName}</span>
            </div>
            <div className="progress-track">
              <div className="progress-fill" style={{ width: `${Math.max(progressPercent, 3)}%` }} />
            </div>
          </div>
        )}
      </div>

      <div className="dialogue-panel" ref={scrollRef}>
        {allMessages.map((d, i) => (
          <div key={i} className={`message-bubble ${d.role}`}>
            <strong>{d.role === 'interviewer' ? '面试官' : '我'}: </strong>
            <p style={{ whiteSpace: 'pre-wrap', marginTop: '0.5rem' }}>{d.content}</p>
          </div>
        ))}
        {streamText && (
          <div className="message-bubble interviewer streaming">
            <strong>面试官: </strong>
            <p style={{ whiteSpace: 'pre-wrap', marginTop: '0.5rem' }}>
              {streamText}
              <span className="stream-caret">▍</span>
            </p>
          </div>
        )}
        {loading && !streamText && <div className="message-bubble interviewer thinking">面试官正在思考...</div>}
      </div>

      <div className="input-area">
        {asrReady === false && (
          <div className="voice-status">
            <span className="voice-text">
              语音输入还没配置：到右上角「设置 → 语音识别」填一个 Key 就能用了（硅基流动有免费额度）。
            </span>
          </div>
        )}
        {(recorder.active || transcribing || voiceError || recorder.error) && (
          <div className={`voice-status ${(voiceError || recorder.error) ? 'is-error' : ''}`}>
            {recorder.active && <span className="voice-dot" />}
            <span className="voice-text">
              {voiceError?.message
                || recorder.error
                || (transcribing
                  ? '正在识别…'
                  : '正在听，说完停顿一下就会自动上屏')}
            </span>
            {voiceError?.code && (
              <code
                className="voice-code"
                title={voiceError.requestId ? `Request ID: ${voiceError.requestId}` : '服务端返回的错误码'}
              >
                {voiceError.code}
              </code>
            )}
            {recorder.active && (
              <span className="voice-meter">
                <i style={{ width: `${Math.min(100, Math.round(recorder.level * 700))}%` }} />
              </span>
            )}
          </div>
        )}
        <textarea
          value={input}
          onChange={e => {
            setInput(e.target.value)
            baseTextRef.current = e.target.value
          }}
          placeholder="输入你的回答，或点「语音」直接说…（Enter 发送，Shift+Enter 换行）"
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
        />
        <div className="input-actions">
          <button
            className={`btn ${recorder.active ? 'danger' : 'secondary'} voice-btn`}
            onClick={toggleVoice}
            disabled={loading || !recorder.supported || asrReady === false}
            title={!recorder.supported
              ? '当前浏览器不支持录音，请使用 Chrome / Edge'
              : asrReady === false
                ? '请先到「设置 → 语音识别」配置一个 Key'
                : (recorder.active ? '停止录音' : '开始语音输入')}
          >
            {recorder.active ? '⏹ 停止' : '🎤 语音'}
          </button>
          <label className="proofread-toggle" title="识别后用大模型修正技术术语，如「对列」改成「队列」">
            <input
              type="checkbox"
              checked={proofread}
              onChange={e => setProofread(e.target.checked)}
            />
            <span>术语纠错</span>
          </label>
          {!recorder.active && input.trim() && (
            <button
              className="btn-link proofread-now"
              onClick={runProofread}
              disabled={proofreading}
              title="把当前文本里的技术术语再校一遍"
            >
              {proofreading ? '校对中…' : '校对术语'}
            </button>
          )}
          <button
            className="btn primary"
            onClick={handleSend}
            disabled={loading || !input.trim() || recorder.active}
            title={recorder.active ? '请先停止录音再发送' : ''}
          >
            发送回答
          </button>
        </div>
      </div>
    </div>
  )
}
