import { useEffect, useRef, useState } from 'react'
import { wsUrl } from './api.js'

/**
 * Collaboration channel for one document.
 *
 * Owns the WebSocket and the operational-transform book-keeping. The Monaco component
 * gets back a small handle: the current text + version (for initialisation), a function
 * to forward local edits, peer presence to render decorations, and a connection status.
 *
 * Concurrency model on the client:
 *   - We apply local edits *optimistically* to Monaco and send them to the server with
 *     our last-known `version` as `baseVersion`. The server transforms them against any
 *     intervening ops and broadcasts the canonical sequence as `Applied`.
 *   - When an `Applied` echoes our own edit (`authorSessionId === ours`), it is an ack:
 *     we just advance our version counter, the Monaco buffer is already correct.
 *   - When an `Applied` is from a peer, we apply each op to Monaco (guarded by a flag so
 *     our own change-listener doesn't try to re-emit it) and advance `version`.
 *
 * This is the naïve "send and wait" model — a single op can be in flight at a time. For
 * a course project on a single document with a handful of editors this is fine; the
 * follow-up is Wave-style send/buffer queueing.
 */
export function useCollab(documentId, userName, editorRef, monacoRef) {
  const [status, setStatus]   = useState('connecting') // 'connecting' | 'open' | 'closed' | 'error'
  const [snapshot, setSnap]   = useState(null)         // { text, version, sessionId, userId }
  const [peers, setPeers]     = useState([])           // Presence[] (excluding self)

  const wsRef           = useRef(null)
  const sessionIdRef    = useRef(null)
  const versionRef      = useRef(0)
  const applyingRemote  = useRef(false) // suppresses the change listener while we mutate
  const peersRef        = useRef(new Map()) // sessionId -> Presence (excluding self)

  useEffect(() => {
    if (!documentId) return undefined
    const ws = new WebSocket(wsUrl(documentId, userName))
    wsRef.current = ws

    ws.addEventListener('open',  () => setStatus('open'))
    ws.addEventListener('error', () => setStatus('error'))
    ws.addEventListener('close', () => setStatus('closed'))

    ws.addEventListener('message', (e) => {
      let msg
      try { msg = JSON.parse(e.data) } catch { return }
      switch (msg.type) {
        case 'snapshot': {
          sessionIdRef.current = msg.sessionId
          versionRef.current   = msg.version
          setSnap({
            text:      msg.text,
            version:   msg.version,
            sessionId: msg.sessionId,
            userId:    msg.userId,
          })
          // The snapshot lists peers present at handshake time. Our own session is
          // never in `peers`, but be defensive in case the server ever adds it.
          const fresh = new Map()
          for (const p of msg.peers ?? []) {
            if (p.sessionId !== msg.sessionId) fresh.set(p.sessionId, p)
          }
          peersRef.current = fresh
          setPeers([...fresh.values()])
          break
        }
        case 'applied': {
          versionRef.current = msg.version
          if (msg.authorSessionId !== sessionIdRef.current) {
            applyRemoteOps(editorRef.current, monacoRef.current, msg.ops, applyingRemote)
          }
          break
        }
        case 'cursor': {
          if (msg.sessionId === sessionIdRef.current) break
          peersRef.current.set(msg.sessionId, {
            sessionId:    msg.sessionId,
            userId:       msg.userId,
            displayName:  msg.displayName,
            cursor:       msg.cursor,
            selectionEnd: msg.selectionEnd,
          })
          setPeers([...peersRef.current.values()])
          break
        }
        case 'peerJoined': {
          const p = msg.presence
          if (p.sessionId === sessionIdRef.current) break
          peersRef.current.set(p.sessionId, p)
          setPeers([...peersRef.current.values()])
          break
        }
        case 'peerLeft': {
          peersRef.current.delete(msg.sessionId)
          setPeers([...peersRef.current.values()])
          break
        }
        case 'error': {
          // Non-fatal: log; a "no such document" handshake error will be followed by
          // the server closing the socket and our `close` listener flipping status.
          console.warn('server error:', msg.reason)
          break
        }
        default:
          console.warn('unknown server msg:', msg)
      }
    })

    return () => {
      // Use 1000 (normal closure) so the server-side finalize doesn't log it as abnormal.
      try { ws.close(1000) } catch { /* already closed */ }
    }
  }, [documentId, userName, editorRef, monacoRef])

  /** Forward Monaco change events. Each event can carry several disjoint edits; we
   *  serialise them in offset order (highest first) so each op's positions are still
   *  valid against the document version we last acked. */
  function sendChanges(changes) {
    if (applyingRemote.current) return
    if (wsRef.current?.readyState !== WebSocket.OPEN) return

    const sorted = [...changes].sort((a, b) => b.rangeOffset - a.rangeOffset)
    for (const c of sorted) {
      if (c.rangeLength > 0) {
        sendOp({ type: 'delete', pos: c.rangeOffset, length: c.rangeLength })
      }
      if (c.text && c.text.length > 0) {
        sendOp({ type: 'insert', pos: c.rangeOffset, text: c.text })
      }
    }
  }

  function sendOp(op) {
    const payload = { type: 'edit', baseVersion: versionRef.current, op }
    wsRef.current.send(JSON.stringify(payload))
  }

  function sendCursor(pos, selectionEnd) {
    if (wsRef.current?.readyState !== WebSocket.OPEN) return
    wsRef.current.send(JSON.stringify({ type: 'cursor', pos, selectionEnd }))
  }

  return {
    status,
    snapshot,
    peers,
    sessionId: sessionIdRef.current,
    applyingRemote, // ref — Editor reads it to suppress its own change emit
    sendChanges,
    sendCursor,
  }
}

/** Apply a list of server ops to the Monaco editor, suppressing our local change
 *  listener for the duration so we don't bounce the same edit back to the server. */
function applyRemoteOps(editor, monaco, ops, applyingRemote) {
  if (!editor || !monaco || !ops?.length) return
  const model = editor.getModel()
  if (!model) return
  applyingRemote.current = true
  try {
    for (const op of ops) {
      if (op.type === 'insert') {
        const pos = model.getPositionAt(op.pos)
        model.applyEdits([{
          range: new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column),
          text:  op.text,
          forceMoveMarkers: true,
        }])
      } else if (op.type === 'delete') {
        const start = model.getPositionAt(op.pos)
        const end   = model.getPositionAt(op.pos + op.length)
        model.applyEdits([{
          range: new monaco.Range(start.lineNumber, start.column, end.lineNumber, end.column),
          text:  '',
          forceMoveMarkers: true,
        }])
      }
    }
  } finally {
    applyingRemote.current = false
  }
}
