// Thin wrapper over the backend REST surface. The dev server proxies `/api/*` to the
// Scala backend (see vite.config.js); in production the same origin will serve both.

/** Sanitize a user-controlled display name before it flows into browser storage or the
 *  WebSocket URL. Keeps printable ASCII letters/digits/spaces and a small punctuation
 *  set, drops everything else, and clamps to 40 characters. This is intentionally
 *  conservative — the backend invents a fallback name if the field is empty. */
export function sanitizeUserName(raw) {
  if (typeof raw !== 'string') return ''
  return raw.replace(/[^A-Za-z0-9 _.-]/g, '').slice(0, 40)
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
