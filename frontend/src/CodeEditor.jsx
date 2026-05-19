import { useEffect, useRef, useState } from 'react'
import Editor from '@monaco-editor/react'
import { useCollab } from './useCollab.js'

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
 */
export default function CodeEditor({ document: doc, userName, onBack }) {
  const editorRef = useRef(null)
  const monacoRef = useRef(null)
  const decorationsRef = useRef(null) // monaco IEditorDecorationsCollection
  const [language, setLanguage] = useState('plaintext')

  const { status, snapshot, peers, sessionId, applyingRemote, sendChanges, sendCursor } =
    useCollab(doc?.id, userName, editorRef, monacoRef)

  // Push peer-colour CSS into the document once. The selectors are scoped by sessionId
  // so removing a peer doesn't leak styles into the next session that takes that slot.
  useEffect(() => {
    if (!peers?.length) return undefined
    const style = window.document.createElement('style')
    style.dataset.peers = 'true'
    style.textContent = peers.map((p) => {
      const c = colourFor(p.sessionId)
      // The class names match those we set in `decorationsRef` below.
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
      const len = model.getValueLength()
      const safeCursor = Math.max(0, Math.min(p.cursor, len))
      const safeSel    = Math.max(0, Math.min(p.selectionEnd, len))
      const cursorPos  = model.getPositionAt(safeCursor)
      const out = [{
        range: new monaco.Range(cursorPos.lineNumber, cursorPos.column, cursorPos.lineNumber, cursorPos.column),
        options: { className: `peer-cursor-${cssId(p.sessionId)}`, stickiness: 1, hoverMessage: { value: p.displayName } },
      }]
      if (safeSel !== safeCursor) {
        const a = Math.min(safeCursor, safeSel)
        const b = Math.max(safeCursor, safeSel)
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
        <select value={language} onChange={(e) => setLanguage(e.target.value)}>
          {LANGUAGES.map((l) => <option key={l}>{l}</option>)}
        </select>
        <button onClick={formatCode}>Format</button>
        <div className="peers">
          {peers.map((p) => (
            <span key={p.sessionId} className="peer-chip" style={{ borderColor: colourFor(p.sessionId) }}>
              {p.displayName}
            </span>
          ))}
          {sessionId && <span className="peer-chip self">{userName} (you)</span>}
        </div>
      </div>

      {ready ? (
        <Editor
          height="calc(100vh - 48px)"
          language={language}
          theme="vs-dark"
          defaultValue={snapshot.text}
          onMount={handleMount}
          options={{ fontSize: 14, wordWrap: 'on', automaticLayout: true }}
        />
      ) : (
        <div className="loading">Connecting…</div>
      )}
    </div>
  )
}

/** Sanitise a UUID into something we can stick into a CSS class. */
function cssId(s) {
  return String(s).replace(/[^a-zA-Z0-9_-]/g, '')
}
