import { useState, useEffect, useRef } from 'react'
import { api } from '../api/client'
import './SmartAssistant.css'

/** 助手回复 → 简化为「Markdown + 纯 URL」渲染 */
function formatAssistantContent(content) {
    if (!content) return ''
    let s = String(content)
    s = s.replace(/\u201C|\u201D|\u201E|\u201F|\u2033|\u2036|\uFF02/g, '"')
        .replace(/&quot;|&#34;|&amp;quot;/g, '"')

    if (s.includes('<a ') || s.includes('href=')) {
        return s
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
            .replace(/\n/g, '<br/>')
    }

    s = s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

    const cleanHref = (u) =>
        (u || '').split('"')[0].split(' ')[0].split('<')[0].trim()

    s = s.replace(
        /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
        (_, text, url) =>
            `<a href="${cleanHref(url)}" target="_blank" rel="noopener noreferrer">${(text || '').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</a>`
    )

    s = s.replace(
        /(?<!href=")(https?:\/\/[^\s<>"')\]\u4e00-\u9fa5,，。、]+)/g,
        (_, url) =>
            `<a href="${cleanHref(url)}" target="_blank" rel="noopener noreferrer">${cleanHref(url)}</a>`
    )

    s = s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    s = s.replace(/\n/g, '<br/>')

    return s
}

export default function SmartAssistant() {
    const [sessions, setSessions] = useState([])
    const [activeSessionId, setActiveSessionId] = useState(null)
    const [activeSessionDbId, setActiveSessionDbId] = useState(null)
    const [messages, setMessages] = useState([])
    const [input, setInput] = useState('')
    const [loading, setLoading] = useState(false)
    const [sidebarOpen, setSidebarOpen] = useState(true)
    const chatEndRef = useRef(null)

    // 加载会话列表
    const loadSessions = async () => {
        try {
            const data = await api.getToolSessions()
            setSessions(Array.isArray(data) ? data : [])
        } catch (e) {
            console.error('加载会话列表失败', e)
        }
    }

    useEffect(() => { loadSessions() }, [])

    useEffect(() => {
        chatEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }, [messages])

    // 新建对话
    const createNewSession = async () => {
        try {
            const { sessionId } = await api.createToolSession()
            setActiveSessionId(sessionId)
            setActiveSessionDbId(null)
            setMessages([])
            loadSessions()
        } catch (e) {
            console.error('创建会话失败', e)
        }
    }

    // 点击历史会话 → 加载消息
    const openSession = async (sess) => {
        if (!sess) return
        setActiveSessionDbId(sess.id)
        setActiveSessionId(sess.sessionId)
        setMessages([])
        try {
            const detail = await api.getToolSession(sess.id)
            if (detail && detail.messages) {
                setMessages(detail.messages.map(m => ({ role: m.role, content: m.content })))
            }
        } catch (e) {
            console.error('加载会话失败', e)
        }
    }

    // 删除会话
    const deleteSession = async (e, sess) => {
        e?.stopPropagation()
        if (!confirm('确定删除此对话？')) return
        try {
            await api.deleteToolSession(sess.id)
            if (activeSessionDbId === sess.id) {
                setActiveSessionId(null)
                setActiveSessionDbId(null)
                setMessages([])
            }
            loadSessions()
        } catch (e) {
            console.error('删除会话失败', e)
        }
    }

    // 发送消息
    const sendMessage = async () => {
        const msg = input.trim()
        if (!msg || loading) return

        // 没有 activeSession 时自动创建
        let sid = activeSessionId
        if (!sid) {
            try {
                const { sessionId } = await api.createToolSession()
                sid = sessionId
                setActiveSessionId(sid)
            } catch (e) {
                console.error('创建会话失败', e)
                return
            }
        }

        setInput('')
        setMessages(prev => [...prev, { role: 'user', content: msg }])
        setLoading(true)
        try {
            const res = await api.toolChat(sid, msg)
            const reply = res?.reply ?? (typeof res === 'string' ? res : '')
            setMessages(prev => [...prev, { role: 'assistant', content: reply }])
            loadSessions() // 刷新列表（标题可能更新）
        } catch (e) {
            console.error(e)
            setMessages(prev => [...prev, { role: 'assistant', content: '回复失败: ' + (e.message || '') }])
        } finally {
            setLoading(false)
        }
    }

    return (
        <div className="smart-assistant">
            {/* 左侧会话列表 */}
            <aside className={`sa-sidebar ${sidebarOpen ? '' : 'collapsed'}`}>
                <div className="sa-sidebar-header">
                    <h3>对话记录</h3>
                    <button className="sa-new-chat" onClick={createNewSession} title="新建对话">
                        ＋ 新对话
                    </button>
                </div>
                <ul className="sa-session-list">
                    {sessions.map(s => (
                        <li
                            key={s.id}
                            className={`sa-session-item ${activeSessionDbId === s.id ? 'active' : ''}`}
                            onClick={() => openSession(s)}
                        >
                            <span className="sa-session-title">{s.title || '新对话'}</span>
                            <span className="sa-session-time">
                                {s.updatedAt ? new Date(s.updatedAt).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''}
                            </span>
                            <button className="sa-session-delete" onClick={(e) => deleteSession(e, s)} title="删除">×</button>
                        </li>
                    ))}
                    {sessions.length === 0 && <li className="sa-session-empty">暂无对话记录</li>}
                </ul>
            </aside>

            {/* 右侧对话区 */}
            <main className="sa-main">
                <div className="sa-header">
                    <button className="sa-toggle-sidebar" onClick={() => setSidebarOpen(!sidebarOpen)} title="切换侧边栏">
                        ☰
                    </button>
                    <h2>🤖 智能助手</h2>
                    <p className="sa-subtitle">可查面经、查题库、运行代码、从小红书抓取面经</p>
                </div>

                <div className="sa-messages">
                    {messages.length === 0 && !loading && (
                        <div className="sa-welcome">
                            <div className="sa-welcome-icon">🤖</div>
                            <h3>你好，我是 sspOffer 智能助手</h3>
                            <p>试试问我：</p>
                            <div className="sa-suggestions">
                                <button onClick={() => { setInput('查一下字节后端的面经'); }}>查一下字节后端的面经</button>
                                <button onClick={() => { setInput('给我一道中等难度的算法题'); }}>给我一道中等难度的算法题</button>
                                <button onClick={() => { setInput('从小红书搜几条美团后端面经'); }}>从小红书搜面经</button>
                            </div>
                        </div>
                    )}
                    {messages.map((m, i) => (
                        <div key={i} className={`sa-msg ${m.role}`}>
                            <span className="sa-msg-avatar">{m.role === 'user' ? '🧑' : '🤖'}</span>
                            <div
                                className="sa-msg-content"
                                style={{ whiteSpace: 'pre-wrap' }}
                                dangerouslySetInnerHTML={{ __html: m.role === 'user' ? m.content.replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br/>') : formatAssistantContent(m.content) }}
                            />
                        </div>
                    ))}
                    {loading && (
                        <div className="sa-msg assistant">
                            <span className="sa-msg-avatar">🤖</span>
                            <div className="sa-msg-content sa-typing">
                                <span className="sa-dot"></span>
                                <span className="sa-dot"></span>
                                <span className="sa-dot"></span>
                                思考中（可能正在调用工具）...
                            </div>
                        </div>
                    )}
                    <div ref={chatEndRef} />
                </div>

                <div className="sa-input-area">
                    <textarea
                        className="sa-input"
                        value={input}
                        onChange={(e) => setInput(e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter' && !e.shiftKey) {
                                e.preventDefault()
                                sendMessage()
                            }
                        }}
                        placeholder="输入问题，Enter 发送，Shift+Enter 换行"
                        rows={2}
                        disabled={loading}
                    />
                    <button
                        className="sa-send"
                        onClick={sendMessage}
                        disabled={loading || !input.trim()}
                    >
                        发送
                    </button>
                </div>
            </main>
        </div>
    )
}
