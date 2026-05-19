import { useCallback, useEffect, useState } from 'react'
import { getDocumentHistory, getHistoryDiff } from './api.js'

const BATCH_WINDOW_MS = 15000
const CONTEXT_LINES   = 3

// ---------------------------------------------------------------------------
// Grouping

function groupEntries(entries) {
  if (!entries.length) return []
  const groups = []
  let batch = [entries[0]]
  for (let i = 1; i < entries.length; i++) {
    const e = entries[i], prev = entries[i - 1]
    if (e.authorDisplayName === prev.authorDisplayName &&
        e.timestamp - prev.timestamp < BATCH_WINDOW_MS) {
      batch.push(e)
    } else {
      groups.push(batch)
      batch = [e]
    }
  }
  groups.push(batch)
  return groups
}

function batchStats(batch) {
  let inserted = 0, deleted = 0, preview = ''
  for (const { op } of batch) {
    if (op.type === 'insert') { inserted += op.text.length; if (!preview) preview = op.text }
    else                      { deleted  += op.length }
  }
  return { inserted, deleted, preview }
}

// ---------------------------------------------------------------------------
// Line diff (LCS-based)

function diffLines(before, after) {
  const a = before.split('\n'), b = after.split('\n')
  const m = a.length, n = b.length
  const dp = Array.from({ length: m + 1 }, () => new Int32Array(n + 1))
  for (let i = 1; i <= m; i++)
    for (let j = 1; j <= n; j++)
      dp[i][j] = a[i-1] === b[j-1] ? dp[i-1][j-1] + 1 : Math.max(dp[i-1][j], dp[i][j-1])
  const result = []
  let i = m, j = n
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && a[i-1] === b[j-1]) { result.unshift({ type: 'same', text: a[i-1] }); i--; j-- }
    else if (j > 0 && (i === 0 || dp[i][j-1] >= dp[i-1][j])) { result.unshift({ type: 'add',  text: b[j-1] }); j-- }
    else                                                       { result.unshift({ type: 'del',  text: a[i-1] }); i-- }
  }
  return result
}

function collapseContext(lines) {
  const near = new Array(lines.length).fill(false)
  lines.forEach((l, i) => {
    if (l.type !== 'same')
      for (let k = Math.max(0, i - CONTEXT_LINES); k <= Math.min(lines.length - 1, i + CONTEXT_LINES); k++)
        near[k] = true
  })
  const out = []
  let skipped = 0
  lines.forEach((l, i) => {
    if (near[i]) { if (skipped) { out.push({ type: 'collapse', count: skipped }); skipped = 0 } out.push(l) }
    else skipped++
  })
  if (skipped) out.push({ type: 'collapse', count: skipped })
  return out
}

// ---------------------------------------------------------------------------
// Components

function formatTime(ms) { return new Date(ms).toLocaleTimeString() }

function DiffView({ before, after }) {
  const raw       = diffLines(before, after)
  const collapsed = collapseContext(raw)
  const hasChanges = raw.some(l => l.type !== 'same')

  if (!hasChanges) return <p className="muted diff-no-change">No textual changes in this batch.</p>

  return (
    <div className="diff-view">
      {collapsed.map((line, i) => {
        if (line.type === 'collapse')
          return <div key={i} className="diff-collapse">… {line.count} unchanged line{line.count !== 1 ? 's' : ''}</div>
        const cls = line.type === 'add' ? 'diff-add' : line.type === 'del' ? 'diff-del' : 'diff-same'
        const prefix = line.type === 'add' ? '+' : line.type === 'del' ? '−' : ' '
        return (
          <div key={i} className={`diff-line ${cls}`}>
            <span className="diff-prefix">{prefix}</span>
            <code>{line.text || ' '}</code>
          </div>
        )
      })}
    </div>
  )
}

// Fallback when the backend diff endpoint is unavailable: render inserted text
// line-by-line from the op data we already have locally.
function OpsView({ batch }) {
  const insertedText = batch
    .filter(e => e.op.type === 'insert')
    .map(e => e.op.text)
    .join('')
  const deletedCount = batch.reduce(
    (n, e) => e.op.type === 'delete' ? n + e.op.length : n, 0
  )

  if (!insertedText && deletedCount === 0)
    return <p className="muted diff-no-change">No changes in this batch.</p>

  return (
    <div className="diff-view">
      {insertedText && insertedText.split('\n').map((line, i) => (
        <div key={`i${i}`} className="diff-line diff-add">
          <span className="diff-prefix">+</span>
          <code>{line || ' '}</code>
        </div>
      ))}
      {deletedCount > 0 && (
        <div className="diff-line diff-del">
          <span className="diff-prefix">−</span>
          <code>{deletedCount} character{deletedCount !== 1 ? 's' : ''} removed</code>
        </div>
      )}
    </div>
  )
}

function BatchEntry({ batch, documentId }) {
  const [open,       setOpen]       = useState(false)
  const [diff,       setDiff]       = useState(null)
  const [loading,    setLoading]    = useState(false)
  const [diffFailed, setDiffFailed] = useState(false)

  const first = batch[0], last = batch[batch.length - 1]
  const { inserted, deleted, preview } = batchStats(batch)
  const previewText  = preview.length > 24 ? preview.slice(0, 24) + '…' : preview
  const timeLabel    = batch.length > 1 ? `${formatTime(first.timestamp)} – ${formatTime(last.timestamp)}` : formatTime(first.timestamp)
  const versionLabel = batch.length > 1 ? `v${first.version}–${last.version}` : `v${first.version}`

  function toggle() {
    if (!open && !diff && !loading && !diffFailed) {
      setLoading(true)
      getHistoryDiff(documentId, first.version, last.version)
        .then(d => { setDiff(d); setLoading(false) })
        .catch(() => { setDiffFailed(true); setLoading(false) })
    }
    setOpen(v => !v)
  }

  return (
    <li className={`history-entry ${open ? 'expanded' : ''}`}>
      <button className="history-entry-btn" onClick={toggle}>
        <span className="history-meta">
          <span className="history-author">{first.authorDisplayName}</span>
          <span className="history-time muted">{timeLabel}</span>
          <span className="history-version muted">{versionLabel}</span>
        </span>
        <span className="history-delta">
          {inserted > 0 && <span className="op-insert">+{inserted}{previewText && <> <code>{previewText}</code></>}</span>}
          {inserted > 0 && deleted > 0 && ' '}
          {deleted  > 0 && <span className="op-delete">−{deleted}</span>}
          {inserted === 0 && deleted === 0 && <span className="muted">no change</span>}
        </span>
        <span className="history-chevron">{open ? '▴' : '▾'}</span>
      </button>
      {open && (
        <div className="history-diff-body">
          {loading    && <p className="muted">Loading diff…</p>}
          {diff       && <DiffView before={diff.before} after={diff.after} />}
          {diffFailed && <OpsView batch={batch} />}
        </div>
      )}
    </li>
  )
}

// ---------------------------------------------------------------------------
// Panel

export default function HistoryPanel({ documentId, onClose }) {
  const [entries, setEntries] = useState(null)
  const [error,   setError]   = useState(null)

  const load = useCallback(() => {
    setError(null)
    getDocumentHistory(documentId)
      .then(setEntries)
      .catch(e => setError(e.message))
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
        {error  && <p className="error">{error}</p>}
        {!error && entries === null && <p className="muted">Loading…</p>}
        {!error && entries?.length === 0 && <p className="muted">No edits yet.</p>}
        {groups.length > 0 && (
          <ol className="history-list">
            {groups.map(batch => (
              <BatchEntry
                key={batch[batch.length - 1].version}
                batch={batch}
                documentId={documentId}
              />
            ))}
          </ol>
        )}
      </div>
    </div>
  )
}
