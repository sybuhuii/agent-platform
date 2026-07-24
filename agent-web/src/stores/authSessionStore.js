const SESSION_KEY = 'agent_session_id'

export function getSessionId() {
  return sessionStorage.getItem(SESSION_KEY) || ''
}

export function setSessionId(sid) {
  if (sid) {
    sessionStorage.setItem(SESSION_KEY, sid)
  }
}

export function clearSession() {
  sessionStorage.removeItem(SESSION_KEY)
}
