import { useEffect, useState } from 'react'
import { createDocument, listDocuments } from './api.js'

/** Lobby screen: pick a document or create a new one. */
export default function DocumentList({ onOpen }) {
  const [docs, setDocs]       = useState(null) // null = loading, [] = loaded empty
  const [error, setError]     = useState(null)
  const [title, setTitle]     = useState('')
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

  return (
    <div className="lobby">
      <h1>Lite-IDE</h1>
      <p className="muted">Pick a document to start editing, or create a new one.</p>

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
        <div className="muted">No documents yet.</div>
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
