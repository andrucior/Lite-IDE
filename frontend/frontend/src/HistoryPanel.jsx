import { useEffect, useState } from 'react'
import { getDocumentHistory } from './api.js'

/** Formats an epoch-ms timestamp as HH:MM:SS. */
function formatTime(ms) {
  return new Date(ms).toLocaleTimeString()
}

/** Renders one op as a short human-readable label. */
function OpLabel({ op }) {
  if (op.type === 'insert') {
    const preview = op.text.length > 20 ? op.text.slice(0, 20) + '…' : op.text
    return <span className="op-insert">+{op.pos} <code>{preview}</code></span>
  }
  return <span className="op-delete">−{op.pos} ({op.length})</span>
}

/**
 * Slide-in panel showing the document's edit history fetched from the backend.
 * Refreshes on open; a manual "Refresh" button allows reloading without close/open.
 */
export default function HistoryPanel({ documentId, onClose }) {
  const [entries, setEntries] = useState(null)
  const [error,   setError]   = useState(null)

  function load() {
    setError(null)
    getDocumentHistory(documentId)
      .then(setEntries)
      .catch((e) => setError(e.message))
  }

  useEffect(() => { load() }, [documentId])

  return (
    <div className="history-panel">
      <div className="history-header">
        <strong>Edit history</strong>
        <div className="history-header-actions">
          <button onClick={load} title="Refresh history">↻</button>
          <button onClick={onClose} title="Close">✕</button>
        </div>
      </div>

      <div className="history-body">
        {error && <p className="error">{error}</p>}
        {!error && entries === null && <p className="muted">Loading…</p>}
        {!error && entries?.length === 0 && (
          <p className="muted">No edits yet.</p>
        )}
        {entries?.length > 0 && (
          <ol className="history-list">
            {[...entries].reverse().map((e, i) => (
              <li key={i} className="history-entry">
                <span className="history-meta">
                  <span className="history-author">{e.authorDisplayName}</span>
                  <span className="history-time muted">{formatTime(e.timestamp)}</span>
                  <span className="history-version muted">v{e.version}</span>
                </span>
                <OpLabel op={e.op} />
              </li>
            ))}
          </ol>
        )}
      </div>
    </div>
  )
}
