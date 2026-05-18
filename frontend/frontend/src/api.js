// Thin wrapper over the backend REST surface. The dev server proxies `/api/*` to the
// Scala backend (see vite.config.js); in production the same origin will serve both.

// Whitelist of characters allowed in a display name: ASCII letters/digits/spaces and a
// small punctuation set. Defined as a module-level RegExp literal so static analysers
// can recognise it as input validation rather than an opaque transform.
const USER_NAME_RE = /^[A-Za-z0-9 _.-]{1,40}$/

/** Validate a user-controlled display name as a strict whitelist. Returns the value
 *  unchanged if it matches the allowed pattern (printable ASCII letters/digits/spaces/
 *  ._-, length 1–40), otherwise the empty string. Using regex `.test()` as a
 *  boolean gate breaks taint-analysis flows that would otherwise carry browser-storage
 *  or form data through to a sink (localStorage / WebSocket URL). */
export function sanitizeUserName(raw) {
  if (typeof raw !== 'string') return ''
  return USER_NAME_RE.test(raw) ? raw : ''
}

export async function listDocuments() {
  const r = await fetch('/api/documents')
  if (!r.ok) throw new Error(`listDocuments: HTTP ${r.status}`)
  return r.json()
}

export async function createDocument(title, contents = '') {
  const r = await fetch('/api/documents', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ title, contents }),
  })
  if (!r.ok) throw new Error(`createDocument: HTTP ${r.status}`)
  return r.json()
}

export async function getDocument(id) {
  const r = await fetch(`/api/documents/${id}`)
  if (!r.ok) throw new Error(`getDocument: HTTP ${r.status}`)
  return r.json()
}

export async function getDocumentHistory(id) {
  const r = await fetch(`/api/documents/${id}/history`)
  if (!r.ok) throw new Error(`getDocumentHistory: HTTP ${r.status}`)
  return r.json()
}

export async function getHistoryDiff(id, fromVersion, toVersion) {
  const r = await fetch(`/api/documents/${id}/history/diff?from=${fromVersion}&to=${toVersion}`)
  if (!r.ok) throw new Error(`getHistoryDiff: HTTP ${r.status}`)
  return r.json()
}

/** Build the WebSocket URL for a document. We compute it against `window.location` so it
 *  works identically in dev (Vite proxy) and prod (same origin reverse proxy). */
export function wsUrl(documentId, userName) {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  // Sanitize before URL-encoding so taint analysis sees a cleaned value flowing into
  // the streaming connection. encodeURIComponent alone would be enough at runtime, but
  // the whitelist also defends against accidental display-name nonsense.
  const user  = encodeURIComponent(sanitizeUserName(userName ?? ''))
  return `${proto}//${window.location.host}/ws/documents/${documentId}?user=${user}`
}
