// 结构化面试「上次填写」的本地草稿，按用户隔离，只存在浏览器本地。
const VERSION = 1
const MAX_RESUME_CHARS = 200000

function storageKey(userId) {
  return `sspoffer:mock-interview-setup:v${VERSION}:${userId || 'anon'}`
}

export function loadSetupDraft(userId) {
  try {
    const raw = localStorage.getItem(storageKey(userId))
    if (!raw) return null
    const data = JSON.parse(raw)
    return data && typeof data === 'object' ? data : null
  } catch (e) {
    return null
  }
}

export function saveSetupDraft(userId, draft) {
  try {
    if (!draft) return
    const payload = {
      ...draft,
      resumeText: String(draft.resumeText || '').slice(0, MAX_RESUME_CHARS)
    }
    localStorage.setItem(storageKey(userId), JSON.stringify(payload))
  } catch (e) {
    // 隐私模式 / 配额不足时忽略，不阻断面试流程
  }
}

export function clearSetupDraft(userId) {
  try {
    localStorage.removeItem(storageKey(userId))
  } catch (e) {
    // ignore
  }
}
