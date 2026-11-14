import { useState, useRef, useEffect, useCallback } from 'react'
import { api } from '../../../api/client'
import { getToken } from '../../../utils/authToken'

const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition

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
  const [isRecording, setIsRecording] = useState(false)
  const scrollRef = useRef(null)
  const recognitionRef = useRef(null)

  // 语音识别切换
  const toggleVoice = useCallback(() => {
    if (!SpeechRecognition) {
      alert('当前浏览器不支持语音识别，请使用 Chrome')
      return
    }

    if (isRecording) {
      recognitionRef.current?.stop()
      setIsRecording(false)
      return
    }

    const recognition = new SpeechRecognition()
    recognition.lang = 'zh-CN'
    recognition.continuous = true
    recognition.interimResults = true

    // 记住录音开始时输入框已有的文本
    const baseText = input

    let finalTranscript = ''

    recognition.onresult = (event) => {
      let interim = ''
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const text = event.results[i][0].transcript
        if (event.results[i].isFinal) {
          // 如果上一段末尾没有标点，自动补上句号
          if (finalTranscript && !/[。！？，；、.!?,;]$/.test(finalTranscript.trim())) {
            finalTranscript += '。'
          }
          finalTranscript += text
        } else {
          interim += text
        }
      }
      // 已有文本 + 语音结果，不会覆盖之前的内容
      setInput(baseText + finalTranscript + interim)
    }

    recognition.onerror = (event) => {
      console.error('语音识别错误:', event.error)
      setIsRecording(false)
    }

    recognition.onend = () => {
      setIsRecording(false)
    }

    recognitionRef.current = recognition
    recognition.start()
    setIsRecording(true)
  }, [isRecording, input])

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
    if (!input.trim() || loading) return
    
    const text = input
    setInput('')
    
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
      setPendingMessages([])
    } finally {
      setLoading(false)
      setStreamText('')
    }
  }

  /** 流式回答：边收边显示，结束后再刷新会话拿到落库的完整对话 */
  const streamAnswer = async (text) => {
    const token = getToken()
    const res = await fetch(`/api/mock-interview/sessions/${session.sessionId}/answer/stream`, {
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
        <textarea
          value={input}
          onChange={e => setInput(e.target.value)}
          placeholder="输入你的回答... (输入完毕按发送)"
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
        />
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button
            className={`btn ${isRecording ? 'danger' : 'secondary'}`}
            onClick={toggleVoice}
            disabled={loading}
            title={isRecording ? '停止录音' : '语音输入'}
            style={{ minWidth: '44px', fontSize: '1.2rem' }}
          >
            {isRecording ? '⏹' : '🎤'}
          </button>
          <button className="btn primary" onClick={handleSend} disabled={loading || !input.trim()}>
            发送回答
          </button>
        </div>
      </div>
    </div>
  )
}
