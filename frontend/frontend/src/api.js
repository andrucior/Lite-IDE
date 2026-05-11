// Thin wrapper over the backend REST surface. The dev server proxies `/api/*` to the
// Scala backend (see vite.config.js); in production the same origin will serve both.

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
  const user  = encodeURIComponent(userName ?? '')
  return `${proto}//${window.location.host}/ws/documents/${documentId}?user=${user}`
}
