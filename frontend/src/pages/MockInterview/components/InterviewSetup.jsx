import { useState } from 'react'

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
  const [formData, setFormData] = useState({
    company: '',
    department: '',
    resumeText: '',
    interviewerStyle: 'GENTLE'
  })
  const [loading, setLoading] = useState(false)
  const [enableMandatory, setEnableMandatory] = useState(false)
  const [mandatoryQuestions, setMandatoryQuestions] = useState([''])
  const [difficulty, setDifficulty] = useState('MEDIUM')
  const [enableCustomCounts, setEnableCustomCounts] = useState(false)
  const [questionCounts, setQuestionCounts] = useState({ ...DEFAULT_COUNTS })

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

  const handleSubmit = async (e) => {
    e.preventDefault()
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
        <label>简历文本（关键提取信息即可）</label>
        <textarea 
          value={formData.resumeText} 
          onChange={e => setFormData({...formData, resumeText: e.target.value})} 
          placeholder="粘贴你的简历项目、实习、技能栈..." 
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

      <button type="submit" className="btn primary" disabled={loading}>
        {loading ? '正在生成面试计划并匹配面经库...' : '开始模拟面试'}
      </button>
    </form>
  )
}
