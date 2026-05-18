import { useCallback, useEffect, useState } from 'react'
import { getDocumentHistory } from './api.js'

const BATCH_WINDOW_MS = 3000

/** Groups consecutive entries from the same author within BATCH_WINDOW_MS into batches. */
function groupEntries(entries) {
  if (!entries.length) return []
  const groups = []
  let batch = [entries[0]]

  for (let i = 1; i < entries.length; i++) {
    const e = entries[i]
    const prev = entries[i - 1]
    const sameAuthor = e.authorDisplayName === prev.authorDisplayName
    const closeInTime = e.timestamp - prev.timestamp < BATCH_WINDOW_MS
    if (sameAuthor && closeInTime) {
      batch.push(e)
    } else {
      groups.push(batch)
      batch = [e]
    }
  }
  groups.push(batch)
  return groups
}

/** Net insert/delete counts for a batch. */
function batchStats(batch) {
  let inserted = 0
  let deleted = 0
  let preview = ''
  for (const { op } of batch) {
    if (op.type === 'insert') { inserted += op.text.length; if (!preview) preview = op.text }
    else                      { deleted  += op.length }
  }
  return { inserted, deleted, preview }
}

function formatTime(ms) {
  return new Date(ms).toLocaleTimeString()
}

function BatchEntry({ batch }) {
  const first = batch[0]
  const last  = batch[batch.length - 1]
  const { inserted, deleted, preview } = batchStats(batch)

  const previewText = preview.length > 24 ? preview.slice(0, 24) + '…' : preview
  const timeLabel   = batch.length > 1
    ? `${formatTime(first.timestamp)} – ${formatTime(last.timestamp)}`
    : formatTime(first.timestamp)
  const versionLabel = batch.length > 1
    ? `v${first.version}–${last.version}`
    : `v${first.version}`

  return (
    <li className="history-entry">
      <span className="history-meta">
        <span className="history-author">{first.authorDisplayName}</span>
        <span className="history-time muted">{timeLabel}</span>
        <span className="history-version muted">{versionLabel}</span>
      </span>
      <span className="history-delta">
        {inserted > 0 && <span className="op-insert">+{inserted} chars{previewText && <> <code>{previewText}</code></>}</span>}
        {inserted > 0 && deleted > 0 && ' '}
        {deleted  > 0 && <span className="op-delete">−{deleted} chars</span>}
        {inserted === 0 && deleted === 0 && <span className="muted">no change</span>}
      </span>
    </li>
  )
}

export default function HistoryPanel({ documentId, onClose }) {
  const [entries, setEntries] = useState(null)
  const [error,   setError]   = useState(null)

  const load = useCallback(() => {
    setError(null)
    getDocumentHistory(documentId)
      .then(setEntries)
      .catch((e) => setError(e.message))
  }, [documentId])

  useEffect(() => { load() }, [documentId, load])

  const groups = entries ? groupEntries(entries).reverse() : []

  return (
    <div className="history-panel">
      <div className="history-header">
        <strong>Edit history</strong>
        <div className="history-header-actions">
          <button onClick={load} title="Refresh">↻</button>
          <button onClick={onClose} title="Close">✕</button>
        </div>
      </div>

      <div className="history-body">
        {error   && <p className="error">{error}</p>}
        {!error && entries === null && <p className="muted">Loading…</p>}
        {!error && entries?.length === 0 && <p className="muted">No edits yet.</p>}
        {groups.length > 0 && (
          <ol className="history-list">
            {groups.map((batch, i) => (
              <BatchEntry key={batch[batch.length - 1].version} batch={batch} />
            ))}
          </ol>
        )}
      </div>
    </div>
  )
}
