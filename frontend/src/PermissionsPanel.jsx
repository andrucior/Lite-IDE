import { useCallback, useEffect, useState } from 'react'
import PropTypes from 'prop-types'
import { addMember, getPermissions, removePermission, setPermission } from './api.js'

/**
 * Side panel for viewing and managing who can access a document.
 *
 * Documents are private: only the owner and explicitly added members can see or edit
 * them. Owners get the full control UI — add people by email, change a member's role,
 * remove a member, and adjust connected peers. Everyone sees their own current role.
 */
export default function PermissionsPanel({ documentId, userId, role, peers, onClose }) {
  const [perms,    setPerms]    = useState(null) // { ownerId, permissions: [{userId, role}] }
  const [error,    setError]    = useState(null)
  const [email,    setEmail]    = useState('')
  const [newRole,  setNewRole]  = useState('editor')
  const [saving,   setSaving]   = useState(false)

  const isOwner = role === 'owner'

  const load = useCallback(() => {
    setError(null)
    getPermissions(documentId)
      .then(setPerms)
      .catch(e => setError(e.message))
  }, [documentId])

  useEffect(() => { load() }, [load])

  async function handleAdd(e) {
    e.preventDefault()
    if (!email.trim()) return
    setSaving(true)
    setError(null)
    try {
      await addMember(documentId, email.trim(), newRole)
      setEmail('')
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
      await removePermission(documentId, targetId)
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
      await setPermission(documentId, peerId, next)
      load()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  let membersContent
  if (perms === null) {
    membersContent = <p className="muted">Loading…</p>
  } else if (perms.permissions.length === 0) {
    membersContent = <p className="muted">No one else has been added yet.</p>
  } else {
    membersContent = (
      <ul className="perms-list">
        {perms.permissions.map(({ userId: uid, role: r }) => (
          <li key={uid} className="perms-row">
            <code className="perms-uid-small">{uid}</code>
            <span className={`role-badge role-${r}`}>{r}</span>
            <button
              disabled={saving}
              onClick={() => handleRemove(uid)}
              title="Remove this member's access"
            >
              Remove
            </button>
          </li>
        ))}
      </ul>
    )
  }

  return (
    <div className="perms-panel">
      <div className="perms-header">
        <strong>Sharing</strong>
        <button onClick={onClose} title="Close">✕</button>
      </div>

      <div className="perms-body">
        {error && <p className="error">{error}</p>}

        {/* Current role indicator — always shown */}
        <section className="perms-section">
          <h4>Your role</h4>
          <span className={`role-badge role-${role}`}>{role ?? '…'}</span>
          {role === 'observer' && (
            <p className="muted perms-hint">You have read-only access to this document.</p>
          )}
          {role === 'editor' && (
            <p className="muted perms-hint">You were added to this workspace by its owner.</p>
          )}
        </section>

        {/* Owner-only controls */}
        {isOwner && (
          <>
            {/* Add a person by email */}
            <section className="perms-section">
              <h4>Add someone</h4>
              <form className="perms-form" onSubmit={handleAdd}>
                <input
                  type="email"
                  placeholder="their email"
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                />
                <select value={newRole} onChange={e => setNewRole(e.target.value)}>
                  <option value="editor">Editor (read + write)</option>
                  <option value="observer">Observer (read-only)</option>
                </select>
                <button type="submit" disabled={saving || !email.trim()}>
                  {saving ? 'Adding…' : 'Add to workspace'}
                </button>
              </form>
              <p className="muted perms-hint">They must already have a Lite-IDE account.</p>
            </section>

            {/* Members */}
            <section className="perms-section">
              <h4>Members</h4>
              {membersContent}
            </section>

            {/* Connected peers */}
            {peers.length > 0 && (
              <section className="perms-section">
                <h4>Connected now</h4>
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
          </>
        )}
      </div>
    </div>
  )
}

PermissionsPanel.propTypes = {
  documentId: PropTypes.string.isRequired,
  userId: PropTypes.string.isRequired,
  role: PropTypes.string,
  peers: PropTypes.arrayOf(PropTypes.shape({
    sessionId: PropTypes.string.isRequired,
    userId: PropTypes.string.isRequired,
    displayName: PropTypes.string.isRequired,
    role: PropTypes.string,
  })).isRequired,
  onClose: PropTypes.func.isRequired,
}
