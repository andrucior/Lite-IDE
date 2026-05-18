import { useCallback, useEffect, useState } from 'react'
import { getPermissions, removePermission, setPermission } from './api.js'

/**
 * Side panel for viewing and managing document permissions.
 *
 * Owners see the full control UI: current explicit grants, connected peers with their
 * roles, and a form to restrict any user to Observer.
 * Non-owners see their own user ID (so they can share it with the owner) and their
 * current role on this document.
 */
export default function PermissionsPanel({ documentId, userId, role, peers, onClose }) {
  const [perms,    setPerms]   = useState(null) // { ownerId, permissions: [{userId, role}] }
  const [error,    setError]   = useState(null)
  const [newId,    setNewId]   = useState('')
  const [newRole,  setNewRole] = useState('observer')
  const [saving,   setSaving]  = useState(false)

  const isOwner = role === 'owner'

  const load = useCallback(() => {
    setError(null)
    getPermissions(documentId)
      .then(setPerms)
      .catch(e => setError(e.message))
  }, [documentId])

  useEffect(() => { load() }, [load])

  async function handleSet(e) {
    e.preventDefault()
    if (!newId.trim()) return
    setSaving(true)
    try {
      await setPermission(documentId, userId, newId.trim(), newRole)
      setNewId('')
      load()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  async function handleRemove(targetId) {
    setSaving(true)
    try {
      await removePermission(documentId, userId, targetId)
      load()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  async function handlePeerRole(peerId, peerRole) {
    const next = peerRole === 'observer' ? 'editor' : 'observer'
    setSaving(true)
    try {
      await setPermission(documentId, userId, peerId, next)
      load()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="perms-panel">
      <div className="perms-header">
        <strong>Permissions</strong>
        <button onClick={onClose} title="Close">✕</button>
      </div>

      <div className="perms-body">
        {error && <p className="error">{error}</p>}

        {/* Your identity — always shown so the user can share their ID */}
        <section className="perms-section">
          <h4>Your user ID</h4>
          <div className="perms-id-row">
            <code className="perms-uid">{userId}</code>
            <button
              className="perms-copy"
              onClick={() => navigator.clipboard.writeText(userId)}
              title="Copy to clipboard"
            >
              Copy
            </button>
          </div>
          <p className="muted perms-hint">
            Share this ID with a document owner to receive a specific role.
          </p>
        </section>

        {/* Current role indicator */}
        <section className="perms-section">
          <h4>Your role</h4>
          <span className={`role-badge role-${role}`}>{role ?? '…'}</span>
          {role === 'observer' && (
            <p className="muted perms-hint">You have read-only access to this document.</p>
          )}
        </section>

        {/* Owner-only controls */}
        {isOwner && (
          <>
            {/* Connected peers */}
            {peers.length > 0 && (
              <section className="perms-section">
                <h4>Connected users</h4>
                <ul className="perms-list">
                  {peers.map(p => (
                    <li key={p.sessionId} className="perms-row">
                      <div className="perms-row-info">
                        <span className="perms-name">{p.displayName}</span>
                        <code className="perms-uid-small">{p.userId}</code>
                      </div>
                      <span className={`role-badge role-${p.role}`}>{p.role}</span>
                      {p.userId !== userId && (
                        <button
                          disabled={saving}
                          onClick={() => handlePeerRole(p.userId, p.role)}
                          title={p.role === 'observer' ? 'Restore editor access' : 'Restrict to observer'}
                        >
                          {p.role === 'observer' ? 'Restore' : 'Restrict'}
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              </section>
            )}

            {/* Explicit grants */}
            <section className="perms-section">
              <h4>Explicit permissions</h4>
              {perms === null ? (
                <p className="muted">Loading…</p>
              ) : perms.permissions.length === 0 ? (
                <p className="muted">No restrictions set — everyone defaults to Editor.</p>
              ) : (
                <ul className="perms-list">
                  {perms.permissions.map(({ userId: uid, role: r }) => (
                    <li key={uid} className="perms-row">
                      <code className="perms-uid-small">{uid}</code>
                      <span className={`role-badge role-${r}`}>{r}</span>
                      <button
                        disabled={saving}
                        onClick={() => handleRemove(uid)}
                        title="Remove restriction (reverts to Editor)"
                      >
                        Remove
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            {/* Add / change permission */}
            <section className="perms-section">
              <h4>Set role for user</h4>
              <form className="perms-form" onSubmit={handleSet}>
                <input
                  placeholder="User ID (UUID)"
                  value={newId}
                  onChange={e => setNewId(e.target.value)}
                />
                <select value={newRole} onChange={e => setNewRole(e.target.value)}>
                  <option value="observer">Observer (read-only)</option>
                  <option value="editor">Editor (read + write)</option>
                </select>
                <button type="submit" disabled={saving || !newId.trim()}>
                  {saving ? 'Saving…' : 'Set'}
                </button>
              </form>
            </section>
          </>
        )}
      </div>
    </div>
  )
}
