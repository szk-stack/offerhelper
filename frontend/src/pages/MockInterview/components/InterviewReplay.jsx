import { useState, useEffect } from 'react'
import { api } from '../../../api/client'
import InterviewReport from './InterviewReport'

export default function InterviewReplay({ sessionId, onBack }) {
  const [session, setSession] = useState(null)
  const [loading, setLoading] = useState(true)
  const [showReport, setShowReport] = useState(false)

  useEffect(() => {
    const load = async () => {
      setLoading(true)
      try {
        const data = await api.getMockSession(sessionId)
        setSession(data)
      } catch (e) {
        console.error('加载面试详情失败:', e)
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [sessionId])

  if (loading) return <div className="replay-container"><p>加载中...</p></div>
  if (!session) return <div className="replay-container"><p>未找到该面试记录</p></div>

  const dialogues = session.dialogues || []

  return (
    <div className="replay-container">
      <div className="replay-header">
        <div>
          <h3>📖 面试回放：{session.company} - {session.department}</h3>
          <span className="replay-meta">
            {session.createdAt && new Date(session.createdAt).toLocaleString('zh-CN')}
            {' · '}共 {dialogues.length} 条对话
          </span>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          {session.report && (
            <button
              className={`btn ${showReport ? 'primary' : 'secondary'}`}
              onClick={() => setShowReport(!showReport)}
            >
              {showReport ? '查看对话' : '查看报告'}
            </button>
          )}
          <button className="btn secondary" onClick={onBack}>← 返回列表</button>
        </div>
      </div>

      {showReport && session.report ? (
        <InterviewReport session={session} />
      ) : (
        <div className="replay-dialogue">
          {dialogues.length === 0 ? (
            <p className="replay-empty">该面试没有对话记录</p>
          ) : (
            dialogues.map((d, i) => (
              <div key={i} className={`message-bubble ${d.role}`}>
                <strong>{d.role === 'interviewer' ? '面试官' : '我'}: </strong>
                <p style={{ whiteSpace: 'pre-wrap', marginTop: '0.5rem' }}>{d.content}</p>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  )
}
