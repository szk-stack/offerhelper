import { useEffect, useRef, useState } from 'react'
import { api } from '../../../api/client'
import { useAuth } from '../../../context/AuthContext'
import { clearSetupDraft, loadSetupDraft, saveSetupDraft } from '../../../utils/setupDraft'

const RESUME_ACCEPT = '.pdf,.jpg,.jpeg,.png,.gif,.webp'
const RESUME_MAX_SIZE = 10 * 1024 * 1024

const DEFAULT_COUNTS = {
  theoretical: 3,
  project: 2,
  scenario: 1
}

const DIFFICULTY_LABELS = {
  EASY: '简单（基础概念为主）',
  MEDIUM: '中等（需要一定深度）',
  HARD: '困难（深入底层原理+复杂场景）'
}

export default function InterviewSetup({ onComplete }) {
  const { user } = useAuth()
  const userId = user?.userId || user?.username || 'anon'
  const [formData, setFormData] = useState({
    company: '',
    department: '',
    resumeText: '',
    interviewerStyle: 'GENTLE'
  })
  const [loading, setLoading] = useState(false)
  const [parsingResume, setParsingResume] = useState(false)
  const [resumeFileName, setResumeFileName] = useState('')
  const [resumeError, setResumeError] = useState('')
  const [draggingResume, setDraggingResume] = useState(false)
  const resumeInputRef = useRef(null)
  const [enableMandatory, setEnableMandatory] = useState(false)
  const [mandatoryQuestions, setMandatoryQuestions] = useState([''])
  const [difficulty, setDifficulty] = useState('MEDIUM')
  const [enableCustomCounts, setEnableCustomCounts] = useState(false)
  const [questionCounts, setQuestionCounts] = useState({ ...DEFAULT_COUNTS })
  const [hydrated, setHydrated] = useState(false)
  const draftRef = useRef(null)

  const buildDraft = () => ({
    company: formData.company,
    department: formData.department,
    resumeText: formData.resumeText,
    resumeFileName,
    interviewerStyle: formData.interviewerStyle,
    difficulty,
    enableCustomCounts,
    questionCounts,
    enableMandatory,
    mandatoryQuestions
  })
  draftRef.current = buildDraft()

  // 进入页面时带出上一次填写的内容（按用户隔离，只在本机）
  useEffect(() => {
    const draft = loadSetupDraft(userId)
    if (draft) {
      setFormData(prev => ({
        ...prev,
        company: typeof draft.company === 'string' ? draft.company : prev.company,
        department: typeof draft.department === 'string' ? draft.department : prev.department,
        resumeText: typeof draft.resumeText === 'string' ? draft.resumeText : prev.resumeText,
        interviewerStyle: draft.interviewerStyle || prev.interviewerStyle
      }))
      if (draft.difficulty) setDifficulty(draft.difficulty)
      if (draft.questionCounts) setQuestionCounts({ ...DEFAULT_COUNTS, ...draft.questionCounts })
      if (typeof draft.enableCustomCounts === 'boolean') setEnableCustomCounts(draft.enableCustomCounts)
      if (typeof draft.enableMandatory === 'boolean') setEnableMandatory(draft.enableMandatory)
      if (Array.isArray(draft.mandatoryQuestions) && draft.mandatoryQuestions.length > 0) {
        setMandatoryQuestions(draft.mandatoryQuestions)
      }
      if (draft.resumeFileName) setResumeFileName(draft.resumeFileName)
    }
    setHydrated(true)
  }, [userId])

  // 改动后自动保存，防抖 300ms
  useEffect(() => {
    if (!hydrated) return
    const timer = setTimeout(() => saveSetupDraft(userId, draftRef.current), 300)
    return () => clearTimeout(timer)
  }, [hydrated, userId, formData, resumeFileName, difficulty, enableCustomCounts, questionCounts, enableMandatory, mandatoryQuestions])

  // 关闭页面或离开路由时立刻落盘
  useEffect(() => {
    if (!hydrated) return
    const flush = () => saveSetupDraft(userId, draftRef.current)
    window.addEventListener('beforeunload', flush)
    return () => {
      window.removeEventListener('beforeunload', flush)
      flush()
    }
  }, [hydrated, userId])

  const handleForget = () => {
    if (!window.confirm('清除本机保存的填写内容？下次进入需要重新填写。')) return
    clearSetupDraft(userId)
    setFormData({ company: '', department: '', resumeText: '', interviewerStyle: 'GENTLE' })
    setDifficulty('MEDIUM')
    setEnableCustomCounts(false)
    setQuestionCounts({ ...DEFAULT_COUNTS })
    setEnableMandatory(false)
    setMandatoryQuestions([''])
    setResumeFileName('')
    setResumeError('')
    if (resumeInputRef.current) resumeInputRef.current.value = ''
  }

  const handleAddQuestion = () => {
    setMandatoryQuestions([...mandatoryQuestions, ''])
  }

  const handleRemoveQuestion = (index) => {
    setMandatoryQuestions(mandatoryQuestions.filter((_, i) => i !== index))
  }

  const handleQuestionChange = (index, value) => {
    const updated = [...mandatoryQuestions]
    updated[index] = value
    setMandatoryQuestions(updated)
  }

  const handleCountChange = (key, value) => {
    const num = Math.max(0, Math.min(10, parseInt(value) || 0))
    setQuestionCounts({ ...questionCounts, [key]: num })
  }

  const handleResumeFile = async (file) => {
    if (!file || parsingResume) return
    setResumeError('')

    const name = file.name || '简历'
    const ext = name.includes('.') ? name.slice(name.lastIndexOf('.')).toLowerCase() : ''
    if (!RESUME_ACCEPT.split(',').includes(ext)) {
      setResumeError('仅支持 PDF 或图片（jpg / png / gif / webp）')
      return
    }
    if (file.size > RESUME_MAX_SIZE) {
      setResumeError('文件不能超过 10MB')
      return
    }

    setParsingResume(true)
    try {
      const data = await api.parseResume(file)
      const content = (data?.content || '').trim()
      if (!content) {
        throw new Error('没有从文件中提取到文字，请换一张更清晰的图片，或直接粘贴简历文本')
      }
      setFormData(prev => ({ ...prev, resumeText: content }))
      setResumeFileName(name)
    } catch (e) {
      setResumeError(e.message || '简历解析失败，请重试')
    } finally {
      setParsingResume(false)
    }
  }

  const clearResume = () => {
    setFormData(prev => ({ ...prev, resumeText: '' }))
    setResumeFileName('')
    setResumeError('')
    if (resumeInputRef.current) resumeInputRef.current.value = ''
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    saveSetupDraft(userId, buildDraft())
    setLoading(true)
    const submitData = { ...formData, difficulty }
    if (enableMandatory) {
      const validQuestions = mandatoryQuestions.filter(q => q.trim())
      if (validQuestions.length > 0) {
        submitData.mandatoryQuestions = validQuestions
      }
    }
    if (enableCustomCounts) {
      submitData.questionCounts = questionCounts
    }
    await onComplete(submitData)
    setLoading(false)
  }

  return (
    <form className="setup-form" onSubmit={handleSubmit}>
      <p className="setup-hint">填写内容会自动保存在本机，下次进入自动带出。</p>
      <div className="form-group">
        <label>目标公司</label>
        <input 
          type="text" 
          value={formData.company} 
          onChange={e => setFormData({...formData, company: e.target.value})} 
          placeholder="如：字节跳动" 
          required 
        />
      </div>
      <div className="form-group">
        <label>目标部门</label>
        <input 
          type="text" 
          value={formData.department} 
          onChange={e => setFormData({...formData, department: e.target.value})} 
          placeholder="如：后端开发" 
          required 
        />
      </div>
      <div className="form-row">
        <div className="form-group" style={{ flex: 1 }}>
          <label>面试官风格</label>
          <select 
            value={formData.interviewerStyle} 
            onChange={e => setFormData({...formData, interviewerStyle: e.target.value})}
          >
            <option value="GENTLE">温和引导型</option>
            <option value="PRESSURE">压力面试型</option>
            <option value="TECH_GEEK">技术极客型</option>
          </select>
        </div>
        <div className="form-group" style={{ flex: 1 }}>
          <label>面试难度</label>
          <select value={difficulty} onChange={e => setDifficulty(e.target.value)}>
            {Object.entries(DIFFICULTY_LABELS).map(([k, v]) => (
              <option key={k} value={k}>{v}</option>
            ))}
          </select>
        </div>
      </div>

      <div className="form-group">
        <label>简历（可上传 PDF / 图片，也可直接粘贴文本）</label>

        <div
          className={`resume-upload ${draggingResume ? 'dragging' : ''} ${parsingResume ? 'parsing' : ''}`}
          onClick={() => !parsingResume && resumeInputRef.current?.click()}
          onDragOver={e => { e.preventDefault(); setDraggingResume(true) }}
          onDragLeave={() => setDraggingResume(false)}
          onDrop={e => {
            e.preventDefault()
            setDraggingResume(false)
            handleResumeFile(e.dataTransfer.files?.[0])
          }}
          role="button"
          tabIndex={0}
          onKeyDown={e => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              resumeInputRef.current?.click()
            }
          }}
        >
          <input
            ref={resumeInputRef}
            type="file"
            accept={RESUME_ACCEPT}
            hidden
            onChange={e => {
              handleResumeFile(e.target.files?.[0])
              e.target.value = ''
            }}
          />
          {parsingResume ? (
            <span className="resume-upload-main">正在解析简历，请稍候…</span>
          ) : (
            <>
              <span className="resume-upload-main">点击上传 PDF / 图片简历</span>
              <span className="resume-upload-sub">
                也可以把文件拖到这里，支持 pdf、jpg、png、gif、webp（不超过 10MB）
              </span>
            </>
          )}
        </div>

        {resumeFileName && !parsingResume && (
          <div className="resume-file-info">
            <span>已解析：{resumeFileName}（{formData.resumeText.length} 字）</span>
            <button type="button" className="btn-link" onClick={clearResume}>清空</button>
          </div>
        )}
        {resumeError && <div className="resume-parse-error">{resumeError}</div>}

        <textarea
          value={formData.resumeText}
          onChange={e => setFormData({ ...formData, resumeText: e.target.value })}
          placeholder="上传文件后会自动填入，也可以直接粘贴简历项目、实习、技能栈..."
          required
        />
      </div>

      {/* 自定义题目数量 */}
      <div className="form-group">
        <label className="toggle-label" onClick={() => setEnableCustomCounts(!enableCustomCounts)} style={{ cursor: 'pointer', userSelect: 'none' }}>
          <span className={`toggle-switch ${enableCustomCounts ? 'active' : ''}`}>
            <span className="toggle-knob" />
          </span>
          自定义各类题目数量
        </label>

        {enableCustomCounts && (
          <div className="counts-config">
            <div className="count-item">
              <label>八股理论题</label>
              <div className="count-control">
                <button type="button" onClick={() => handleCountChange('theoretical', questionCounts.theoretical - 1)}>−</button>
                <span>{questionCounts.theoretical}</span>
                <button type="button" onClick={() => handleCountChange('theoretical', questionCounts.theoretical + 1)}>+</button>
              </div>
            </div>
            <div className="count-item">
              <label>项目/实习深挖</label>
              <div className="count-control">
                <button type="button" onClick={() => handleCountChange('project', questionCounts.project - 1)}>−</button>
                <span>{questionCounts.project}</span>
                <button type="button" onClick={() => handleCountChange('project', questionCounts.project + 1)}>+</button>
              </div>
            </div>
            <div className="count-item">
              <label>场景设计题</label>
              <div className="count-control">
                <button type="button" onClick={() => handleCountChange('scenario', questionCounts.scenario - 1)}>−</button>
                <span>{questionCounts.scenario}</span>
                <button type="button" onClick={() => handleCountChange('scenario', questionCounts.scenario + 1)}>+</button>
              </div>
            </div>
            <p className="count-hint">总计约 {questionCounts.theoretical + questionCounts.project + questionCounts.scenario + 2} 题（含自我介绍和反问环节）</p>
          </div>
        )}
      </div>

      {/* 必问问题开关 */}
      <div className="form-group">
        <label className="toggle-label" onClick={() => setEnableMandatory(!enableMandatory)} style={{ cursor: 'pointer', userSelect: 'none' }}>
          <span className={`toggle-switch ${enableMandatory ? 'active' : ''}`}>
            <span className="toggle-knob" />
          </span>
          自定义必问问题
        </label>

        {enableMandatory && (
          <div className="mandatory-questions">
            {mandatoryQuestions.map((q, i) => (
              <div key={i} className="mandatory-question-row">
                <input
                  type="text"
                  value={q}
                  onChange={e => handleQuestionChange(i, e.target.value)}
                  placeholder={`必问问题 ${i + 1}，如：讲一下 HashMap 的底层原理`}
                />
                {mandatoryQuestions.length > 1 && (
                  <button type="button" className="btn-icon danger" onClick={() => handleRemoveQuestion(i)} title="删除">✕</button>
                )}
              </div>
            ))}
            <button type="button" className="btn secondary btn-add-question" onClick={handleAddQuestion}>
              + 添加问题
            </button>
          </div>
        )}
      </div>

      <div className="setup-actions">
        <button type="submit" className="btn primary" disabled={loading}>
          {loading ? '正在生成面试计划并匹配面经库...' : '开始模拟面试'}
        </button>
        <button type="button" className="btn-link" onClick={handleForget}>
          清除本机保存
        </button>
      </div>
    </form>
  )
}
