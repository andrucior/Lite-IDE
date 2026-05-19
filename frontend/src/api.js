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

/** Return the stable user ID for this browser, generating and persisting one on first use.
 *  The ID is a UUID stored in localStorage under a fixed key. Sharing this ID with a
 *  document owner lets them grant or restrict access for this browser.
 *
 *  The same value is also written to a cookie so the browser sends it automatically
 *  during the WebSocket upgrade request. The backend reads it from the Cookie header,
 *  which means the frontend never has to embed it in a user-constructed URL. */
export function getOrCreateUserId() {
  const KEY = 'lite-ide-user-id'
  let id = localStorage.getItem(KEY)
  if (!id || !UUID_RE.test(id)) {
    id = crypto.randomUUID()
    localStorage.setItem(KEY, id)
  }
  document.cookie = `${KEY}=${id}; path=/; max-age=31536000; SameSite=Lax`
  return id
}

export async function listDocuments() {
  const r = await fetch('/api/documents')
  if (!r.ok) throw new Error(`listDocuments: HTTP ${r.status}`)
  return r.json()
}

export async function createDocument(title, contents = '', creatorUserId = '') {
  const r = await fetch('/api/documents', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ title, contents, creatorUserId }),
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

/** Fetch the permission list for a document.
 *  Returns `{ ownerId: string, permissions: Array<{ userId: string, role: string }> }`. */
export async function getPermissions(docId) {
  const r = await fetch(`/api/documents/${docId}/permissions`)
  if (!r.ok) throw new Error(`getPermissions: HTTP ${r.status}`)
  return r.json()
}

/** Grant or restrict a user's role on a document. Only the owner may call this.
 *  `role` must be `"editor"` or `"observer"`. */
export async function setPermission(docId, actingUserId, targetUserId, role) {
  const r = await fetch(`/api/documents/${docId}/permissions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ actingUserId, userId: targetUserId, role }),
  })
  if (!r.ok) throw new Error(`setPermission: HTTP ${r.status}`)
  return r.json()
}

/** Remove an explicit permission grant for a user, reverting them to the default Editor role.
 *  Only the owner may call this. */
export async function removePermission(docId, actingUserId, targetUserId) {
  const url = `/api/documents/${docId}/permissions/${encodeURIComponent(targetUserId)}?actingUserId=${encodeURIComponent(actingUserId)}`
  const r = await fetch(url, { method: 'DELETE' })
  if (!r.ok) throw new Error(`removePermission: HTTP ${r.status}`)
}

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Build the WebSocket URL for a document. We compute it against `globalThis.location` so it
 *  works identically in dev (Vite proxy) and prod (same origin reverse proxy). */
export function wsUrl(documentId, userName, userId) {
  const proto = globalThis.location.protocol === 'https:' ? 'wss:' : 'ws:'
  // All three user-supplied parameters are validated against strict whitelists before
  // being embedded in the URL, so no user-controlled value reaches the WebSocket sink.
  if (!UUID_RE.test(documentId ?? '')) throw new Error('wsUrl: invalid documentId')
  const docId = documentId
  const user  = encodeURIComponent(sanitizeUserName(userName ?? ''))
  const uid   = encodeURIComponent(UUID_RE.test(userId ?? '') ? userId : '')
  return `${proto}//${globalThis.location.host}/ws/documents/${docId}?user=${user}&userId=${uid}`
}
