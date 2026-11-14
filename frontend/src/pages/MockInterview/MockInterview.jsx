import { useState, useEffect, useCallback, useRef } from 'react'
import { api } from '../../api/client'
import InterviewSetup from './components/InterviewSetup'
import InterviewRoom from './components/InterviewRoom'
import InterviewReport from './components/InterviewReport'
import InterviewHistory from './components/InterviewHistory'
import InterviewReplay from './components/InterviewReplay'
import './MockInterview.css'

const REPORT_POLL_INTERVAL = 3000
const REPORT_POLL_MAX = 40

export default function MockInterview() {
  const [session, setSession] = useState(null) // null, string (sessionId), or object
  const [view, setView] = useState('setup') // 'setup' | 'history' | 'replay'
  const [replaySessionId, setReplaySessionId] = useState(null)
  const [busy, setBusy] = useState(false)
  const [generatingReport, setGeneratingReport] = useState(false)
  const pollCount = useRef(0)

  const loadSession = useCallback(async (sessionId) => {
    try {
      const res = await api.getMockSession(sessionId)
      if (res) setSession(res)
      return res
    } catch (e) {
      console.error(e)
      return null
    }
  }, [])

  // 报告异步生成：完成后端状态置为 SUCCESS / FAILED，这里轮询到结果为止
  const reportPending = !!(
    session &&
    session.status === 'COMPLETED' &&
    (!session.report || session.report.status === 'GENERATING')
  )

  useEffect(() => {
    if (!reportPending || !session) return
    const sessionId = session.sessionId
    pollCount.current = 0
    const timer = setInterval(async () => {
      pollCount.current += 1
      if (pollCount.current > REPORT_POLL_MAX) {
        clearInterval(timer)
        return
      }
      try {
        const data = await api.getMockSession(sessionId)
        if (data) setSession(data)
      } catch (e) {
        console.error('轮询报告失败', e)
        clearInterval(timer)
      }
    }, REPORT_POLL_INTERVAL)
    return () => clearInterval(timer)
  }, [reportPending, session?.sessionId])

  // 1. 创建会话 -> 生成计划 -> 开始面试
  const handleSetupComplete = async (formData) => {
    let sid = null
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
      sid = res.sessionId
    } catch (e) {
      alert('创建面试失败: ' + e.message)
      return
    }

    setBusy(true)
    try {
      await api.generateMockPlan(sid)
      await api.startMockInterview(sid)
      await loadSession(sid)
    } catch (e) {
      // 会话已经建好，别丢掉：加载出来让用户从「继续准备」接着走
      await loadSession(sid)
      alert('面试准备中断: ' + e.message + '\n可以点击「继续准备」重试，不会丢简历和配置。')
    } finally {
      setBusy(false)
    }
  }

  // 从 CREATED / PLAN_READY 继续准备
  const handlePrepare = async () => {
    if (!session) return
    setBusy(true)
    try {
      if (session.status === 'CREATED' || !session.plan) {
        await api.generateMockPlan(session.sessionId)
      }
      await api.startMockInterview(session.sessionId)
      await loadSession(session.sessionId)
    } catch (e) {
      alert('继续准备失败: ' + e.message)
      await loadSession(session.sessionId)
    } finally {
      setBusy(false)
    }
  }

  const handlePause = async () => {
    if (!session) return
    try {
      await api.pauseMockInterview(session.sessionId)
      await loadSession(session.sessionId)
    } catch (e) {
      alert('暂停失败: ' + e.message)
    }
  }

  const handleResume = async () => {
    if (!session) return
    try {
      await api.resumeMockInterview(session.sessionId)
      await loadSession(session.sessionId)
    } catch (e) {
      alert('继续面试失败: ' + e.message)
    }
  }

  const handleEndInterview = async (sessionId) => {
    try {
      await api.endMockInterview(sessionId)
      // 先把本地状态置为「生成中」，避免报告出来前闪一下旧内容
      setSession(prev => prev ? { ...prev, status: 'COMPLETED', report: { ...(prev.report || {}), status: 'GENERATING' } } : prev)
      await loadSession(sessionId)
    } catch (e) {
      alert('结束面试失败: ' + e.message)
    }
  }

  const handleAbort = async () => {
    if (!session) return
    if (!confirm('确定放弃这场面试吗？对话记录会保留，之后仍可补生成报告。')) return
    try {
      await api.abortMockInterview(session.sessionId)
      await loadSession(session.sessionId)
    } catch (e) {
      alert('放弃面试失败: ' + e.message)
    }
  }

  const handleRegenerateReport = async () => {
    if (!session) return
    setGeneratingReport(true)
    try {
      await api.regenerateMockReport(session.sessionId)
      setSession(prev => prev ? { ...prev, report: { ...(prev.report || {}), status: 'GENERATING', errorMessage: null } } : prev)
      await loadSession(session.sessionId)
    } catch (e) {
      alert('重新生成报告失败: ' + e.message)
    } finally {
      setGeneratingReport(false)
    }
  }

  const handleViewHistory = () => {
    setView('history')
  }

  const handleSelectReplay = async (sessionId, status) => {
    if (status === 'IN_PROGRESS' || status === 'PAUSED') {
      try {
        const data = await api.getMockSession(sessionId)
        if (data) {
          setSession(data)
          setView('setup')
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

  const handleNewInterview = () => {
    setSession(null)
    setView('setup')
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

  // 准备中：创建一个会话后计划没生成成功，或计划就绪但还没开始
  if (session && (session.status === 'CREATED' || session.status === 'PLAN_READY')) {
    return (
      <div className="mock-interview-container">
        <div className="session-panel">
          <h2>面试还没开始</h2>
          <p className="session-panel-hint">
            {session.company || '目标公司'} · {session.department || '目标部门'}
            {' '}· 状态：{session.status === 'CREATED' ? '待生成面试计划' : '计划已就绪'}
          </p>
          <div className="session-panel-actions">
            <button className="btn primary" onClick={handlePrepare} disabled={busy}>
              {busy ? '正在准备...' : '继续准备'}
            </button>
            <button className="btn secondary" onClick={handleAbort} disabled={busy}>放弃这场面试</button>
            <button className="btn secondary" onClick={handleNewInterview} disabled={busy}>重新填写</button>
          </div>
        </div>
      </div>
    )
  }

  // 已暂停
  if (session && session.status === 'PAUSED') {
    return (
      <div className="mock-interview-container">
        <div className="session-panel">
          <h2>面试已暂停</h2>
          <p className="session-panel-hint">
            {session.company} - {session.department} · 已记录 {(session.dialogues || []).length} 条对话，进度不会丢
          </p>
          <div className="session-panel-actions">
            <button className="btn primary" onClick={handleResume}>继续面试</button>
            <button className="btn secondary" onClick={() => handleEndInterview(session.sessionId)}>结束并出报告</button>
            <button className="btn secondary" onClick={handleAbort}>放弃</button>
          </div>
        </div>
      </div>
    )
  }

  // 面试中
  if (session && session.status === 'IN_PROGRESS') {
    return (
      <div className="mock-interview-container">
        <InterviewRoom
          session={session}
          onEnd={() => handleEndInterview(session.sessionId)}
          onPause={handlePause}
          onUpdate={() => loadSession(session.sessionId)}
        />
      </div>
    )
  }

  // 已放弃：保留记录，可补报告
  if (session && session.status === 'ABORTED') {
    return (
      <div className="mock-interview-container">
        <div className="session-panel">
          <h2>这场面试已放弃</h2>
          <p className="session-panel-hint">
            共 {(session.dialogues || []).length} 条对话已保存，可以根据这些内容补一份评估报告。
          </p>
          <div className="session-panel-actions">
            <button className="btn primary" onClick={handleRegenerateReport} disabled={generatingReport}>
              {generatingReport ? '正在生成...' : '补生成报告'}
            </button>
            <button className="btn secondary" onClick={() => { setReplaySessionId(session.sessionId); setView('replay') }}>
              查看对话
            </button>
            <button className="btn secondary" onClick={handleNewInterview}>开始新面试</button>
          </div>
        </div>
      </div>
    )
  }

  // 面试完成 - 显示报告
  if (session && session.status === 'COMPLETED') {
    return (
      <div className="mock-interview-container">
        <InterviewReport
          session={session}
          onRegenerate={handleRegenerateReport}
          regenerating={generatingReport}
        />
        <div style={{ display: 'flex', gap: '1rem', marginTop: '1rem' }}>
          <button onClick={handleNewInterview} className="btn primary">
            开始新面试
          </button>
          <button onClick={handleViewHistory} className="btn secondary">
            历史记录
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
        <button className="btn secondary" onClick={handleViewHistory}>历史记录</button>
      </div>
      {busy ? (
        <div className="session-panel">
          <h2>正在生成面试计划并匹配面经库...</h2>
          <p className="session-panel-hint">通常需要 10-30 秒，请不要关闭页面</p>
        </div>
      ) : (
        <InterviewSetup onComplete={handleSetupComplete} />
      )}
    </div>
  )
}
