import { useEffect, useState } from 'react'
import PropTypes from 'prop-types'
import { createDocument, listDocuments, logout } from './api.js'

/** Lobby screen: the user's own and shared workspaces, plus a create form. */
export default function DocumentList({ account, onOpen, onLoggedOut }) {
  const [docs,     setDocs]     = useState(null) // null = loading, [] = loaded empty
  const [error,    setError]    = useState(null)
  const [title,    setTitle]    = useState('')
  const [creating, setCreating] = useState(false)

  async function refresh() {
    try {
      setDocs(await listDocuments())
      setError(null)
    } catch (e) {
      setError(e.message)
      setDocs([])
    }
  }

  useEffect(() => { refresh() }, [])

  async function onCreate(e) {
    e.preventDefault()
    if (!title.trim()) return
    setCreating(true)
    try {
      const doc = await createDocument(title.trim())
      setTitle('')
      await refresh()
      onOpen(doc)
    } catch (e) {
      setError(e.message)
    } finally {
      setCreating(false)
    }
  }

  async function handleLogout() {
    try { await logout() } catch { /* clear the session locally regardless */ }
    onLoggedOut()
  }

  return (
    <div className="lobby">
      <div className="account-bar">
        <span className="muted">
          Signed in as <strong>{account.displayName || account.email}</strong>
        </span>
        <button onClick={handleLogout}>Log out</button>
      </div>

      <h1>Your workspaces</h1>
      <p className="muted">Open one of your documents, or create a new one.</p>

      <form className="create-row" onSubmit={onCreate}>
        <input
          placeholder="new document title"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          autoFocus
        />
        <button type="submit" disabled={creating || !title.trim()}>
          {creating ? 'Creating…' : 'Create'}
        </button>
      </form>

      {error && <div className="error">Error: {error}</div>}

      {docs === null ? (
        <div className="loading">Loading…</div>
      ) : docs.length === 0 ? (
        <div className="muted">No workspaces yet — create one above, or ask an owner to add you.</div>
      ) : (
        <ul className="doc-list">
          {docs.map((d) => (
            <li key={d.id}>
              <button className="doc-row" onClick={() => onOpen(d)}>
                <span className="doc-title">{d.title}</span>
                <span className="doc-meta">v{d.version}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

DocumentList.propTypes = {
  account: PropTypes.shape({
    id: PropTypes.string.isRequired,
    email: PropTypes.string.isRequired,
    displayName: PropTypes.string,
  }).isRequired,
  onOpen: PropTypes.func.isRequired,
  onLoggedOut: PropTypes.func.isRequired,
}
