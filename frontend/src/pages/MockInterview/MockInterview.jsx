import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import InterviewSetup from './components/InterviewSetup'
import InterviewRoom from './components/InterviewRoom'
import InterviewReport from './components/InterviewReport'
import InterviewHistory from './components/InterviewHistory'
import InterviewReplay from './components/InterviewReplay'
import './MockInterview.css'

export default function MockInterview() {
  const [session, setSession] = useState(null) // null, string (sessionId), or object
  const [view, setView] = useState('setup') // 'setup' | 'history' | 'replay'
  const [replaySessionId, setReplaySessionId] = useState(null)

  // 检查是否在面试中
  const loadSession = async (sessionId) => {
    try {
      const res = await api.getMockSession(sessionId)
      if (res) setSession(res)
    } catch (e) {
      console.error(e)
    }
  }

  // 1. 创建会话 -> 返回 sessionId (进入 PLANNING)
  const handleSetupComplete = async (formData) => {
    try {
      const res = await api.createMockSession(
        formData.company,
        formData.department,
        formData.resumeText,
        formData.interviewerStyle,
        formData.mandatoryQuestions,
        formData.difficulty,
        formData.questionCounts
      )
      
      const sid = res.sessionId
      // 生成面试计划
      await api.generateMockPlan(sid)
      // 开始面试
      const startRes = await api.startMockInterview(sid)
      
      // 加载完整 session
      loadSession(sid)
    } catch (e) {
      alert('创建面试失败: ' + e.message)
    }
  }

  const handleEndInterview = async (sessionId) => {
    try {
      await api.endMockInterview(sessionId)
      loadSession(sessionId)
    } catch (e) {
      alert('结束面试失败: ' + e.message)
    }
  }

  const handleViewHistory = () => {
    setView('history')
  }

  const handleSelectReplay = async (sessionId, status) => {
    if (status === 'IN_PROGRESS') {
      // 恢复进行中的面试
      try {
        const data = await api.getMockSession(sessionId)
        if (data) {
          setSession(data)
          setView('setup') // 回到默认视图，让 session 状态驱动显示 InterviewRoom
        }
      } catch (e) {
        alert('恢复面试失败: ' + e.message)
      }
    } else {
      setReplaySessionId(sessionId)
      setView('replay')
    }
  }

  const handleBackToSetup = () => {
    setView('setup')
    setReplaySessionId(null)
  }

  const handleBackToHistory = () => {
    setView('history')
    setReplaySessionId(null)
  }

  // 历史记录视图
  if (view === 'history') {
    return (
      <div className="mock-interview-container">
        <InterviewHistory onSelect={handleSelectReplay} onBack={handleBackToSetup} />
      </div>
    )
  }

  // 对话回放视图
  if (view === 'replay' && replaySessionId) {
    return (
      <div className="mock-interview-container">
        <InterviewReplay sessionId={replaySessionId} onBack={handleBackToHistory} />
      </div>
    )
  }

  // 面试中
  if (session && session.status !== 'COMPLETED') {
    return (
      <div className="mock-interview-container">
        <InterviewRoom
          session={session}
          onEnd={() => handleEndInterview(session.sessionId)}
          onUpdate={() => loadSession(session.sessionId)}
        />
      </div>
    )
  }

  // 面试完成 - 显示报告
  if (session && session.status === 'COMPLETED') {
    return (
      <div className="mock-interview-container">
        <InterviewReport session={session} />
        <div style={{ display: 'flex', gap: '1rem', marginTop: '1rem' }}>
          <button onClick={() => { setSession(null); setView('setup') }} className="btn primary">
            开始新面试
          </button>
          <button onClick={handleViewHistory} className="btn secondary">
            📋 历史记录
          </button>
        </div>
      </div>
    )
  }

  // 默认 - setup
  return (
    <div className="mock-interview-container">
      <div className="setup-title-bar">
        <h2>模拟结构化面试</h2>
        <button className="btn secondary" onClick={handleViewHistory}>📋 历史记录</button>
      </div>
      <InterviewSetup onComplete={handleSetupComplete} />
    </div>
  )
}
