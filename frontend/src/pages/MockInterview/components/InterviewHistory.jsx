import { useState, useEffect } from 'react'
import { api } from '../../../api/client'

const STATUS_MAP = {
  CREATED: { label: '已创建', color: '#94a3b8' },
  PLAN_READY: { label: '计划就绪', color: '#60a5fa' },
  IN_PROGRESS: { label: '进行中', color: '#fbbf24' },
  PAUSED: { label: '已暂停', color: '#f59e0b' },
  COMPLETED: { label: '已完成', color: '#34d399' },
  ABORTED: { label: '已放弃', color: '#94a3b8' },
}

const REPORT_STATUS_MAP = {
  GENERATING: '报告生成中',
  SUCCESS: '报告已生成',
  FAILED: '报告生成失败',
  EMPTY: '无对话记录',
}

export default function InterviewHistory({ onSelect, onBack }) {
  const [sessions, setSessions] = useState([])
  const [loading, setLoading] = useState(true)
  const [regeneratingId, setRegeneratingId] = useState(null)

  const loadSessions = async () => {
    setLoading(true)
    try {
      const data = await api.getMockSessions()
      setSessions(data || [])
    } catch (e) {
      console.error('加载历史记录失败:', e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { loadSessions() }, [])

  const handleDelete = async (e, id) => {
    e.stopPropagation()
    if (!confirm('确定删除这条面试记录吗？')) return
    try {
      await api.deleteMockSession(id)
      setSessions(prev => prev.filter(s => s.id !== id))
    } catch (e) {
      alert('删除失败: ' + e.message)
    }
  }

  const handleRegenerate = async (e, sessionId, id) => {
    e.stopPropagation()
    setRegeneratingId(id)
    try {
      await api.regenerateMockReport(sessionId)
      setSessions(prev => prev.map(s => s.id === id ? { ...s, reportStatus: 'GENERATING' } : s))
      // 报告是异步生成的，稍后刷新一次列表
      setTimeout(loadSessions, 6000)
    } catch (e) {
      alert('生成报告失败: ' + e.message)
    } finally {
      setRegeneratingId(null)
    }
  }

  const formatTime = (t) => {
    if (!t) return '-'
    const d = new Date(t)
    return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
  }

  if (loading) {
    return <div className="history-container"><p>加载中...</p></div>
  }

  return (
    <div className="history-container">
      <div className="history-header">
        <h3>面试历史记录</h3>
        <button className="btn secondary" onClick={onBack}>← 返回</button>
      </div>

      {sessions.length === 0 ? (
        <div className="history-empty">
          <p>暂无面试记录，去开始一场新的模拟面试吧！</p>
        </div>
      ) : (
        <div className="history-list">
          {sessions.map(s => {
            const st = STATUS_MAP[s.status] || { label: s.status, color: '#94a3b8' }
            const finished = s.status === 'COMPLETED' || s.status === 'ABORTED'
            const reportText = REPORT_STATUS_MAP[s.reportStatus]
            const canRegenerate = finished && (s.reportStatus === 'FAILED' || s.reportStatus === null)
            return (
              <div key={s.id} className="history-card" onClick={() => onSelect(s.sessionId, s.status)}>
                <div className="history-card-main">
                  <div className="history-card-title">
                    <strong>{s.company || '未知公司'}</strong>
                    <span className="history-dept">{s.department || '未知部门'}</span>
                  </div>
                  <div className="history-card-meta">
                    <span className="history-status" style={{ background: st.color }}>{st.label}</span>
                    <span className="history-time">{formatTime(s.createdAt)}</span>
                    {s.dialogueCount > 0 && <span className="history-time">{s.dialogueCount} 条对话</span>}
                    {reportText && (
                      <span className={`history-report-status ${s.reportStatus?.toLowerCase() || ''}`}>
                        {s.reportStatus === 'GENERATING' && regeneratingId !== s.id ? '报告生成中' : reportText}
                      </span>
                    )}
                  </div>
                </div>
                <div className="history-card-actions">
                  {s.reportStatus === 'SUCCESS' && s.reportScore !== null && s.reportScore !== undefined && (
                    <span className="history-score">
                      {s.reportScore}分 · {s.reportLevel}
                    </span>
                  )}
                  {canRegenerate && (
                    <button
                      className="btn secondary btn-mini"
                      onClick={(e) => handleRegenerate(e, s.sessionId, s.id)}
                      disabled={regeneratingId === s.id}
                    >
                      {regeneratingId === s.id ? '生成中...' : '补报告'}
                    </button>
                  )}
                  <button className="btn-icon danger" onClick={(e) => handleDelete(e, s.id)} title="删除">删除</button>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
