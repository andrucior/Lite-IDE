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
 *   - Only ONE op is in flight at a time. Additional local ops are pushed onto a
 *     `pendingOps` queue; we send the next one only after the server's `applied` ack
 *     advances `versionRef`. This is the simple "send-and-wait" model: it guarantees
 *     every op we send carries a `baseVersion` that is actually consistent with the
 *     document state the server will transform against. Without this gating, two
 *     rapid local edits would share the same `baseVersion`, causing the server to
 *     transform the second one against the first as if they had been authored from
 *     the same base — but the second one's positions are already relative to the
 *     post-first text, so positions would silently drift.
 *   - When an `Applied` echoes our own edit (`authorSessionId === ours`), it is an ack:
 *     we advance `versionRef`, clear `inFlight`, and pump the next queued op. The
 *     Monaco buffer is already correct from the optimistic apply.
 *   - When an `Applied` is from a peer, we apply each op to Monaco (guarded by a flag
 *     so our own change-listener doesn't try to re-emit it) and advance `versionRef`.
 *
 * Follow-up is Wave-style send/buffer queueing with client-side transform — that lets
 * us pipeline more than one op without waiting, at the cost of mirroring the OT logic
 * on the client. Out of scope for this PR.
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
  const inFlightRef     = useRef(false)     // true while an edit waits for its `applied` ack
  const pendingOpsRef   = useRef([])        // ops produced by Monaco while one was in flight

  /** If nothing is in flight and the queue is non-empty, send the next op and mark
   *  the slot busy. Called both on enqueue and on receiving our own `applied` ack. */
  function pumpPending() {
    if (inFlightRef.current) return
    if (wsRef.current?.readyState !== WebSocket.OPEN) return
    const op = pendingOpsRef.current.shift()
    if (!op) return
    inFlightRef.current = true
    const payload = { type: 'edit', baseVersion: versionRef.current, op }
    wsRef.current.send(JSON.stringify(payload))
  }

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
          if (msg.authorSessionId === sessionIdRef.current) {
            // Ack for our own in-flight op — release the gate and pump the next queued op.
            inFlightRef.current = false
            pumpPending()
          } else {
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
   *  valid against the document version we last acked. Ops are not sent directly —
   *  they pass through `enqueueOp` so the single-in-flight invariant is preserved. */
  function sendChanges(changes) {
    if (applyingRemote.current) return
    if (wsRef.current?.readyState !== WebSocket.OPEN) return

    const sorted = [...changes].sort((a, b) => b.rangeOffset - a.rangeOffset)
    for (const c of sorted) {
      if (c.rangeLength > 0) {
        enqueueOp({ type: 'delete', pos: c.rangeOffset, length: c.rangeLength })
      }
      if (c.text && c.text.length > 0) {
        enqueueOp({ type: 'insert', pos: c.rangeOffset, text: c.text })
      }
    }
  }

  function enqueueOp(op) {
    pendingOpsRef.current.push(op)
    pumpPending()
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
