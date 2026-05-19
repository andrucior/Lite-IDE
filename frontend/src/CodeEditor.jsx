import { useEffect, useRef, useState } from 'react'
import PropTypes from 'prop-types'
import Editor from '@monaco-editor/react'
import { useCollab } from './useCollab.js'
import PermissionsPanel from './PermissionsPanel.jsx'

const LANGUAGES = ['plaintext', 'javascript', 'typescript', 'python', 'scala', 'css', 'html', 'json']

// Stable palette for peer cursors. We hash the sessionId UUID into the palette so a
// given peer keeps the same colour across reconnects within one tab session.
const PEER_COLOURS = ['#e6194b', '#3cb44b', '#ffe119', '#4363d8', '#f58231', '#911eb4', '#46f0f0', '#f032e6']
function colourFor(sessionId) {
  let h = 0
  for (let i = 0; i < sessionId.length; i++) h = (h * 31 + sessionId.charCodeAt(i)) >>> 0
  return PEER_COLOURS[h % PEER_COLOURS.length]
}

/**
 * Monaco-backed collaborative editor for one document.
 *
 * The component is intentionally thin: all OT bookkeeping lives in `useCollab`, and
 * the Monaco surface only wires its events into that hook. The two non-obvious bits:
 *
 *   1. We delay populating Monaco's buffer until the WebSocket `snapshot` arrives.
 *      Otherwise the user could type into a stale editor before the server's version
 *      is known and we'd never reconcile.
 *   2. We inject per-peer cursor decorations via a dynamic `<style>` tag — Monaco's
 *      decoration API only takes a class name, so the colour has to come from CSS.
 *   3. Observers get a read-only Monaco instance — the server also rejects their edits,
 *      so this is defence-in-depth rather than the sole guard.
 */
export default function CodeEditor({ document: doc, userName, userId, onBack }) {
  const editorRef      = useRef(null)
  const monacoRef      = useRef(null)
  const decorationsRef = useRef(null) // monaco IEditorDecorationsCollection
  const [language, setLanguage]     = useState('plaintext')
  const [showPerms, setShowPerms]   = useState(false)

  const { status, snapshot, peers, role, sessionId, applyingRemote, sendChanges, sendCursor } =
    useCollab(doc?.id, userName, editorRef, monacoRef)

  const isObserver = role === 'observer'

  // Push peer-colour CSS into the document once. The selectors are scoped by sessionId
  // so removing a peer doesn't leak styles into the next session that takes that slot.
  useEffect(() => {
    if (!peers?.length) return undefined
    const style = window.document.createElement('style')
    style.dataset.peers = 'true'
    style.textContent = peers.map((p) => {
      const c = colourFor(p.sessionId)
      return `
        .peer-cursor-${cssId(p.sessionId)} {
          border-left: 2px solid ${c};
          margin-left: -1px;
        }
        .peer-selection-${cssId(p.sessionId)} {
          background: ${c}33;
        }
      `
    }).join('\n')
    window.document.head.appendChild(style)
    return () => style.remove()
  }, [peers])

  // Apply / refresh peer cursor decorations whenever presence changes.
  useEffect(() => {
    const editor = editorRef.current
    const monaco = monacoRef.current
    if (!editor || !monaco) return
    const model = editor.getModel()
    if (!model) return

    const decos = peers.flatMap((p) => {
      const len        = model.getValueLength()
      const safeCursor = Math.max(0, Math.min(p.cursor, len))
      const safeSel    = Math.max(0, Math.min(p.selectionEnd, len))
      const cursorPos  = model.getPositionAt(safeCursor)
      const out = [{
        range: new monaco.Range(cursorPos.lineNumber, cursorPos.column, cursorPos.lineNumber, cursorPos.column),
        options: { className: `peer-cursor-${cssId(p.sessionId)}`, stickiness: 1, hoverMessage: { value: p.displayName } },
      }]
      if (safeSel !== safeCursor) {
        const a     = Math.min(safeCursor, safeSel)
        const b     = Math.max(safeCursor, safeSel)
        const start = model.getPositionAt(a)
        const end   = model.getPositionAt(b)
        out.push({
          range: new monaco.Range(start.lineNumber, start.column, end.lineNumber, end.column),
          options: { className: `peer-selection-${cssId(p.sessionId)}` },
        })
      }
      return out
    })

    if (!decorationsRef.current) {
      decorationsRef.current = editor.createDecorationsCollection(decos)
    } else {
      decorationsRef.current.set(decos)
    }
  }, [peers])

  function handleMount(editor, monaco) {
    editorRef.current = editor
    monacoRef.current = monaco

    editor.onDidChangeModelContent((e) => {
      sendChanges(e.changes)
    })

    editor.onDidChangeCursorSelection((e) => {
      if (applyingRemote.current) return
      const model = editor.getModel()
      if (!model) return
      const sel    = e.selection
      const cursor = model.getOffsetAt({ lineNumber: sel.positionLineNumber, column: sel.positionColumn })
      const anchor = model.getOffsetAt({ lineNumber: sel.selectionStartLineNumber, column: sel.selectionStartColumn })
      sendCursor(cursor, anchor)
    })
  }

  function formatCode() {
    editorRef.current?.getAction('editor.action.formatDocument')?.run()
  }

  // Wait for the snapshot so we never let the user type into a doc whose version is
  // not yet known. While loading we show a placeholder bar.
  const ready = snapshot !== null

  return (
    <div className="editor-shell">
      <div className="toolbar">
        <button onClick={onBack} title="Back to document list">← Documents</button>
        <strong>{doc.title}</strong>
        <span className={`status status-${status}`}>{status}</span>
        {role && <span className={`role-badge role-${role}`}>{role}</span>}
        <select value={language} onChange={(e) => setLanguage(e.target.value)}>
          {LANGUAGES.map((l) => <option key={l}>{l}</option>)}
        </select>
        {!isObserver && <button onClick={formatCode}>Format</button>}
        <button
          onClick={() => setShowPerms(v => !v)}
          title="Document permissions"
          style={{ marginLeft: 'auto' }}
        >
          Permissions
        </button>
        <div className="peers">
          {peers.map((p) => (
            <span key={p.sessionId} className="peer-chip" style={{ borderColor: colourFor(p.sessionId) }}>
              {p.displayName}
              {p.role === 'observer' && <span className="peer-role-hint"> 👁</span>}
            </span>
          ))}
          {sessionId && <span className="peer-chip self">{userName} (you)</span>}
        </div>
      </div>

      {isObserver && (
        <div className="observer-banner">
          You have read-only access to this document.
        </div>
      )}

      <div className="editor-main">
        <div style={{ flex: 1, overflow: 'hidden', position: 'relative' }}>
          {ready ? (
            <Editor
              height="100%"
              language={language}
              theme="vs-dark"
              defaultValue={snapshot.text}
              onMount={handleMount}
              options={{
                fontSize: 14,
                wordWrap: 'on',
                automaticLayout: true,
                readOnly: isObserver,
              }}
            />
          ) : (
            <div className="loading">Connecting…</div>
          )}
        </div>

        {showPerms && (
          <PermissionsPanel
            documentId={doc.id}
            userId={userId}
            role={role}
            peers={peers}
            onClose={() => setShowPerms(false)}
          />
        )}
      </div>
    </div>
  )
}

CodeEditor.propTypes = {
  document: PropTypes.shape({
    id: PropTypes.string.isRequired,
    title: PropTypes.string.isRequired,
  }).isRequired,
  userName: PropTypes.string.isRequired,
  userId: PropTypes.string.isRequired,
  onBack: PropTypes.func.isRequired,
}

/** Sanitise a UUID into something we can stick into a CSS class. */
function cssId(s) {
  return String(s).replace(/[^a-zA-Z0-9_-]/g, '')
}
