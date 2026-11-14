const TOKEN_KEY = 'ssp_auth_token'

export const AUTH_EVENT = 'ssp-auth-changed'

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch (e) {
    return null
  }
}

export function setToken(token) {
  try {
    localStorage.setItem(TOKEN_KEY, token)
  } catch (e) { }
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY)
  } catch (e) { }
}

export function notifyAuthChanged() {
  window.dispatchEvent(new Event(AUTH_EVENT))
}
