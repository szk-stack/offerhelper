// 开发时若代理 404，可设置 VITE_API_BASE=http://127.0.0.1:8080/api 直接连后端
// 部署到子路径（如 /interview/）时，BASE_URL 会自动带上前缀，无需再改这里
export const API_BASE = import.meta.env.VITE_API_BASE || `${import.meta.env.BASE_URL}api`

import { getPassword, getPasswordAsync, clearPassword, setPassword } from '../utils/adminAuth'
import { getToken, clearToken, notifyAuthChanged } from '../utils/authToken'

function withAuthHeaders(options, isModify, password) {
  const headers = { ...(options.headers || {}) }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (isModify && password) headers['X-Admin-Password'] = password
  return { ...options, headers }
}

async function doFetch(url, options, isModify) {
  let res = await fetch(url, withAuthHeaders(options, isModify, getPassword()))
  if (res.status === 401 && !url.includes('/auth/login') && !url.includes('/auth/register')) {
    clearToken()
    notifyAuthChanged()
    throw new Error('登录已过期，请重新登录')
  }
  if (isModify && res.status === 403) {
    clearPassword()
    const p = await getPasswordAsync()
    if (!p) throw new Error('需要管理员密码')
    // 重试时使用新对象并显式带上密码头，避免部分环境未正确发送自定义 header
    res = await fetch(url, withAuthHeaders(options, isModify, p))
    if (res.status === 403) {
      clearPassword()
      throw new Error('密码错误，请重试')
    }
    if (p) setPassword(p)
  }
  return res
}

async function request(path, options = {}, requireAuth = false) {
  const method = (options.method || 'GET').toUpperCase()
  const isModify = ['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)
  const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
  const url = base.startsWith('http') ? `${base}${path}` : `${base}${path}`
  const opts = {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  }
  const res = await doFetch(url, opts, isModify || requireAuth)
  if (!res.ok) {
    let msg = res.statusText
    try {
      const body = await res.clone().json()
      if (body?.message) msg = body.message
      else if (body?.error) msg = body.error
    } catch { }
    throw new Error(`${res.status} ${msg}`)
  }
  const text = await res.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

export const api = {
  // 账号
  register: (username, password, displayName) =>
    request('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password, displayName }),
    }),
  login: (username, password) =>
    request('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  logout: () => request('/auth/logout', { method: 'POST' }),
  me: () => request('/auth/me'),
  changePassword: (oldPassword, newPassword) =>
    request('/auth/password', {
      method: 'POST',
      body: JSON.stringify({ oldPassword, newPassword }),
    }),

  // 大模型配置
  getLlmSettings: () => request('/settings/llm'),
  saveLlmSettings: (config) =>
    request('/settings/llm', { method: 'PUT', body: JSON.stringify(config) }),
  resetLlmSettings: () => request('/settings/llm', { method: 'DELETE' }),
  testLlmSettings: (config) =>
    request('/settings/llm/test', { method: 'POST', body: JSON.stringify(config) }),

  // 语音识别配置
  getAsrSettings: () => request('/settings/asr'),
  saveAsrSettings: (config) =>
    request('/settings/asr', { method: 'PUT', body: JSON.stringify(config) }),
  resetAsrSettings: () => request('/settings/asr', { method: 'DELETE' }),
  testAsrSettings: (config) =>
    request('/settings/asr/test', { method: 'POST', body: JSON.stringify(config) }),

  /** 上传一段 WAV 转文字，返回 { text, provider, elapsedMs } 或 { error } */
  transcribeAudio: async (blob) => {
    const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
    const fd = new FormData()
    fd.append('audio', blob, 'segment.wav')
    const res = await doFetch(`${base}/asr/transcribe`, { method: 'POST', headers: {}, body: fd }, true)
    if (!res.ok) {
      let msg = `${res.status} ${res.statusText}`
      try {
        const body = await res.json()
        if (body?.error) msg = body.error
      } catch (_) { }
      throw new Error(msg)
    }
    return res.json()
  },

  /** 术语校对：只修技术术语错别字，不改语气 */
  proofreadText: (text) =>
    request('/asr/proofread', { method: 'POST', body: JSON.stringify({ text }) }),

  // 面经
  getCompanies: () => request('/interviews/companies'),
  getDepartments: (company) => request(`/interviews/companies/${encodeURIComponent(company)}/departments`),
  searchInterviews: (company, department) => {
    const params = new URLSearchParams({ company })
    if (department) params.append('department', department)
    return request(`/interviews/search?${params}`)
  },
  addExperiences: (experiences) =>
    request('/interviews/experiences', {
      method: 'POST',
      body: JSON.stringify(experiences),
    }),
  deleteExperience: async (id) => {
    const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
    const doDelete = async (url) => {
      const res = await doFetch(url, { method: 'DELETE', headers: {} }, true)
      if (res.ok) return true
      const text = await res.text()
      throw new Error(text || res.statusText)
    }
    const doPostDelete = async (url) => {
      const opts = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id }) }
      const res = await doFetch(url, opts, true)
      if (res.ok) return true
      const text = await res.text()
      throw new Error(text || res.statusText)
    }
    try {
      await doPostDelete(`${base}/interviews/experiences/delete`)
      return
    } catch (_) { }
    try {
      await doDelete(`${base}/interviews/experiences/${id}`)
      return
    } catch (_) { }
    try {
      const proxyUrl = base.startsWith('http') ? `${base}/interviews/experiences/delete` : `${base}/interviews/experiences/delete`
      await doPostDelete(proxyUrl)
    } catch (e) {
      throw new Error(e.message || '删除失败')
    }
  },
  parseImage: async (file) => {
    const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
    const directUrl = `${base}/interviews/parseImage`
    const proxyUrl = base.startsWith('http') ? `${base}/interviews/parseImage` : `${base}/interviews/parseImage`
    const doParse = async (targetUrl) => {
      const fd = new FormData()
      fd.append('image', file)
      return doFetch(targetUrl, { method: 'POST', headers: {}, body: fd }, true)
    }
    // 图片解析：先直连后端（代理常 404），失败再试代理
    let res
    try {
      res = await doParse(directUrl)
      if (res.ok) return res.json()
    } catch (_) { }
    try {
      res = await doParse(proxyUrl)
    } catch (_) { }
    if (!res || !res.ok) {
      let msg = res ? (res.status + ' ' + res.statusText) : '网络错误，请确认后端已启动'
      try {
        const body = await res?.json?.()
        if (body?.error) msg = body.error
        else if (body?.message) msg = body.message
      } catch (_) { }
      throw new Error(msg)
    }
    return res.json()
  },
  // 复盘（超时 3 分钟；先试代理，失败再直连后端，减少 Failed to fetch）
  analyzeReplay: async (company, department, content) => {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), 180000)
    const body = JSON.stringify({ company, department, content })
    let opts = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body, signal: ctrl.signal }
    const p = getPassword()
    if (p) opts.headers = { ...opts.headers, 'X-Admin-Password': p }
    const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
    const proxyUrl = base.startsWith('http') ? `${base}/replay/analyze` : `${base}/replay/analyze`
    const directUrl = `${base}/replay/analyze`
    try {
      let res = await doFetch(proxyUrl, opts, true)
      if (res?.ok) {
        clearTimeout(t)
        const text = await res.text()
        try { return text ? JSON.parse(text) : null } catch { return text }
      }
      if (res.status >= 400) {
        clearTimeout(t)
        const err = await res.text()
        let msg = err
        try { const j = JSON.parse(err); msg = j?.error || j?.message || err } catch (_) { }
        throw new Error(`${res.status} ${msg}`)
      }
    } catch (e) {
      if (e?.name === 'AbortError') {
        clearTimeout(t)
        throw new Error('请求超时（3 分钟）')
      }
      // 代理失败时再试直连
      try {
        const res = await doFetch(directUrl, opts, true)
        clearTimeout(t)
        if (!res.ok) {
          const err = await res.text()
          let msg = err
          try { const j = JSON.parse(err); msg = j?.error || j?.message || err } catch (_) { }
          throw new Error(`${res.status} ${msg}`)
        }
        const text = await res.text()
        try { return text ? JSON.parse(text) : null } catch { return text }
      } catch (e2) {
        clearTimeout(t)
        throw e2
      }
    }
    clearTimeout(t)
    throw new Error('请求失败')
  },
  saveReplay: (record) =>
    request('/replay/save', {
      method: 'POST',
      body: JSON.stringify(record),
    }),
  getReplayRecords: () => request('/replay/records'),
  deleteReplayRecord: (id) =>
    request(`/replay/records/${id}`, { method: 'DELETE' }),

  // 简历
  uploadResume: async (file) => {
    const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
    const url = base.startsWith('http') ? `${base}/resumes/upload` : `${base}/resumes/upload`
    const fd = new FormData()
    fd.append('file', file)
    const res = await doFetch(url, { method: 'POST', headers: {}, body: fd }, true)
    if (!res.ok) {
      const err = await res.text()
      let msg = err
      try { const j = JSON.parse(err); msg = j?.error || err } catch (_) { }
      throw new Error(msg)
    }
    return res.json()
  },
  /** 带管理员密码下载简历（他人无法直接通过 URL 下载） */
  downloadResume: async (id) => {
    const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
    const url = base.startsWith('http') ? `${base}/resumes/${id}/download` : `${window.location.origin}${base}/resumes/${id}/download`
    const p = getPassword()
    let opts = { method: 'GET', headers: p ? { 'X-Admin-Password': p } : {} }
    let res = await doFetch(url, opts, true)
    if (res.status === 403) {
      clearPassword()
      const pw = await getPasswordAsync()
      if (!pw) throw new Error('需要管理员密码')
      res = await doFetch(url, { method: 'GET', headers: { 'X-Admin-Password': pw } }, true)
      if (res.status === 403) { clearPassword(); throw new Error('密码错误，请重试') }
      if (pw) setPassword(pw)
    }
    if (!res.ok) throw new Error(res.statusText)
    const blob = await res.blob()
    const disp = res.headers.get('Content-Disposition') || ''
    const m = disp.match(/filename\*?=(?:UTF-8'')?([^;\s]+)/i)
    const name = m ? decodeURIComponent(m[1].replace(/^"|"$/g, '')) : 'resume.pdf'
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = decodeURIComponent(name)
    a.click()
    URL.revokeObjectURL(a.href)
  },
  /** 带管理员密码预览简历（他人无法直接通过 URL 预览） */
  previewResume: async (id) => {
    const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
    const url = base.startsWith('http') ? `${base}/resumes/${id}/preview` : `${window.location.origin}${base}/resumes/${id}/preview`
    const p = getPassword()
    let opts = { method: 'GET', headers: p ? { 'X-Admin-Password': p } : {} }
    let res = await doFetch(url, opts, true)
    if (res.status === 403) {
      clearPassword()
      const pw = await getPasswordAsync()
      if (!pw) throw new Error('需要管理员密码')
      res = await doFetch(url, { method: 'GET', headers: { 'X-Admin-Password': pw } }, true)
      if (res.status === 403) { clearPassword(); throw new Error('密码错误，请重试') }
      if (pw) setPassword(pw)
    }
    if (!res.ok) throw new Error(res.statusText)
    const blob = await res.blob()
    const u = URL.createObjectURL(blob)
    window.open(u, '_blank')
  },
  parseResume: async (file) => {
    const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
    const directUrl = `${base}/resumes/parse`
    const proxyUrl = base.startsWith('http') ? `${base}/resumes/parse` : `${base}/resumes/parse`
    const doParse = async (url) => {
      const fd = new FormData()
      fd.append('file', file)
      return doFetch(url, { method: 'POST', headers: {}, body: fd }, true)
    }
    let res
    try {
      res = await doParse(directUrl)
      if (res.ok) return res.json()
    } catch (_) { }
    try {
      res = await doParse(proxyUrl)
    } catch (_) { }
    if (!res || !res.ok) {
      let msg = res ? (res.status + ' ' + res.statusText) : '网络错误'
      try {
        const body = await res?.json?.()
        if (body?.error) msg = body.error
      } catch (_) { }
      throw new Error(msg)
    }
    return res.json()
  },
  getResumes: () => request('/resumes', {}, true),
  getResume: (id) => request(`/resumes/${id}`, {}, true),
  deleteResume: (id) =>
    request(`/resumes/${id}`, { method: 'DELETE' }),

  // 投递进度
  getApplications: () => request('/applications'),
  createApplication: async (record) => {
    const base = API_BASE.endsWith('/') ? API_BASE.slice(0, -1) : API_BASE
    const url = base.startsWith('http') ? `${base}/applications` : `${base}/applications`
    const directUrl = `${base}/applications`
    const opts = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(record) }
    const handleRes = (res) => {
      if (res.ok) return res.json()
      return res.text().then((err) => {
        let msg = err
        try { const j = JSON.parse(err); msg = j?.error || j?.message || err } catch (_) { }
        throw new Error(msg)
      })
    }
    try {
      const res = await doFetch(url, opts, true)
      return await handleRes(res)
    } catch (e) {
      try {
        const res = await doFetch(directUrl, opts, true)
        return await handleRes(res)
      } catch (e2) {
        throw e2.message ? e2 : new Error('网络错误，请确认后端已启动')
      }
    }
  },
  updateApplication: (id, record) =>
    request(`/applications/${id}`, {
      method: 'PUT',
      body: JSON.stringify(record),
    }),
  deleteApplication: (id) =>
    request(`/applications/${id}`, { method: 'DELETE' }),

  // 代码执行
  executeCode: (language, code, stdin, acmMode) =>
    request('/execute', {
      method: 'POST',
      body: JSON.stringify({ language, code, stdin, acmMode }),
    }),

  // Mock Interview
  createMockSession: (company, department, resumeText, interviewerStyle, mandatoryQuestions, difficulty, questionCounts, mode) =>
    request('/mock-interview/sessions', {
      method: 'POST',
      body: JSON.stringify({ company, department, resumeText, interviewerStyle, mandatoryQuestions, difficulty, questionCounts, mode }),
    }),
  generateMockPlan: (sessionId) =>
    request(`/mock-interview/sessions/${sessionId}/plan`, { method: 'POST' }),
  startMockInterview: (sessionId) =>
    request(`/mock-interview/sessions/${sessionId}/start`, { method: 'POST' }),
  answerMockInterview: (sessionId, answer) =>
    request(`/mock-interview/sessions/${sessionId}/answer`, {
      method: 'POST',
      body: JSON.stringify({ answer }),
    }),
  endMockInterview: (sessionId) =>
    request(`/mock-interview/sessions/${sessionId}/end`, { method: 'POST' }),
  pauseMockInterview: (sessionId) =>
    request(`/mock-interview/sessions/${sessionId}/pause`, { method: 'POST' }),
  resumeMockInterview: (sessionId) =>
    request(`/mock-interview/sessions/${sessionId}/resume`, { method: 'POST' }),
  abortMockInterview: (sessionId) =>
    request(`/mock-interview/sessions/${sessionId}/abort`, { method: 'POST' }),
  regenerateMockReport: (sessionId) =>
    request(`/mock-interview/sessions/${sessionId}/report`, { method: 'POST' }),
  getMockProgress: (sessionId) => request(`/mock-interview/sessions/${sessionId}/progress`),
  getMockSessions: () => request('/mock-interview/sessions'),
  getMockSession: (sessionId) => request(`/mock-interview/sessions/${sessionId}`),
  deleteMockSession: (id) => request(`/mock-interview/sessions/${id}`, { method: 'DELETE' }),
}
