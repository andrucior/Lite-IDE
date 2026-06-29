// Thin wrapper over the backend REST surface. The dev server proxies `/api/*` to the
// Scala backend (see vite.config.js); in production the same origin will serve both.
//
// Auth is cookie-based: register/login set an HttpOnly JWT cookie that the browser then
// sends automatically with every same-origin request (including the collab WebSocket
// upgrade). The frontend never sees or stores the token — it only tracks the account
// object returned by `/api/auth/me`.

// Whitelist of characters allowed in a display name: Unicode letters/digits (so names with
// diacritics like "Paweł" or "José" are accepted), plus spaces and a small punctuation set.
// The `u` flag enables the \p{...} Unicode property escapes. Control characters, quotes,
// angle brackets and slashes are all excluded, so this stays a strict whitelist. Defined as
// a module-level RegExp literal so static analysers can recognise it as input validation
// rather than an opaque transform.
const USER_NAME_RE = /^[\p{L}\p{N} _.-]{1,40}$/u

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Validate a user-controlled display name as a strict whitelist. Returns the value
 *  unchanged if it matches the allowed pattern (Unicode letters/digits, spaces and
 *  ._-, length 1–40), otherwise the empty string. Using regex `.test()` as a
 *  boolean gate breaks taint-analysis flows that would otherwise carry browser-storage
 *  or form data through to a sink (the WebSocket URL). */
export function sanitizeUserName(raw) {
  if (typeof raw !== 'string') return ''
  return USER_NAME_RE.test(raw) ? raw : ''
}

/** Error carrying the HTTP status so callers can branch on it (e.g. 401 → logged out,
 *  409 → email taken). `message` is the backend's `error` field when present. */
export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

// Callback invoked whenever an authenticated request comes back 401 — the app registers
// one (see App.jsx) to drop the user to the login screen when their session ends.
let onUnauthorized = null

/** Register (or clear, with `null`) the global "session ended" handler. */
export function setOnUnauthorized(fn) {
  onUnauthorized = fn
}

/** Single fetch helper. Cookies ride along automatically (same-origin default). Throws
 *  `ApiError` on any non-2xx, using the backend's `{ "error": ... }` body as the message
 *  when it has one. A 401 also fires the global `onUnauthorized` handler (unless suppressed,
 *  e.g. during the initial session probe) so the UI can redirect to login. */
async function request(path, { method = 'GET', body, suppressUnauthorized = false } = {}) {
  const opts = {
    method,
    headers: {},
    credentials:  'include'
  }
  const API_BASE = import.meta.env.VITE_API_URL || '';

  if (body !== undefined) {
    opts.headers['content-type'] = 'application/json'
    opts.body = JSON.stringify(body)
  }
  const r = await fetch(`${API_BASE}${path}`, opts)
  if (!r.ok) {
    if (r.status === 401 && !suppressUnauthorized && onUnauthorized) onUnauthorized()
    let detail = `HTTP ${r.status}`
    try {
      const j = await r.json()
      if (j && typeof j.error === 'string') detail = j.error
    } catch { /* response had no JSON body */ }
    throw new ApiError(r.status, detail)
  }
  if (r.status === 204) return null
  const text = await r.text()
  return text ? JSON.parse(text) : null
}

// ------------------------------------------------------------------ auth

/** Current account `{ id, email, displayName }`, or `null` if not logged in. The 401 here
 *  is the expected "not logged in" answer, so it must not trigger the global redirect. */
export async function me() {
  try {
    return await request('/api/auth/me', { suppressUnauthorized: true })
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return null
    throw e
  }
}

export function register(email, displayName, password) {
  return request('/api/auth/register', { method: 'POST', body: { email, displayName, password } })
}

export function login(email, password) {
  return request('/api/auth/login', { method: 'POST', body: { email, password } })
}

export function logout() {
  return request('/api/auth/logout', { method: 'POST' })
}

// -------------------------------------------------------------- documents

/** Documents the logged-in user can access (owned + shared with them). */
export function listDocuments() {
  return request('/api/documents')
}

/** Create a document. The owner is the authenticated user — there is no owner field to
 *  pass; the backend reads it from the auth cookie. */
export function createDocument(title, contents = '') {
  return request('/api/documents', { method: 'POST', body: { title, contents } })
}

export function getDocument(id) {
  return request(`/api/documents/${id}`)
}

export function getDocumentHistory(id) {
  return request(`/api/documents/${id}/history`)
}

export function getHistoryDiff(id, fromVersion, toVersion) {
  return request(`/api/documents/${id}/history/diff?from=${fromVersion}&to=${toVersion}`)
}

// ------------------------------------------------------------ permissions

/** Fetch the membership list for a document.
 *  Returns `{ ownerId: string, permissions: Array<{ userId: string, role: string }> }`. */
export function getPermissions(docId) {
  return request(`/api/documents/${docId}/permissions`)
}

/** Add a person to a document by their login email. Owner only.
 *  `role` is `"editor"` (default) or `"observer"`. */
export function addMember(docId, email, role = 'editor') {
  return request(`/api/documents/${docId}/members`, { method: 'POST', body: { email, role } })
}

/** Change an existing member's role. Owner only. `role` is `"editor"` or `"observer"`.
 *  The acting user comes from the auth cookie. */
export function setPermission(docId, targetUserId, role) {
  return request(`/api/documents/${docId}/permissions`, { method: 'POST', body: { userId: targetUserId, role } })
}

/** Remove a member, revoking their access. Owner only. */
export function removePermission(docId, targetUserId) {
  return request(`/api/documents/${docId}/permissions/${encodeURIComponent(targetUserId)}`, { method: 'DELETE' })
}
