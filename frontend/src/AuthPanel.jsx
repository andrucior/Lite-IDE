import { useState } from 'react'
import PropTypes from 'prop-types'
import { ApiError, login, register } from './api.js'

/**
 * Combined login / register screen shown whenever there is no active session.
 *
 * One form, two modes toggled by the tabs at the top. On success the backend has already
 * set the auth cookie, and we hand the returned account up to `onAuthed` so the app can
 * switch to the document list.
 */
export default function AuthPanel({ onAuthed }) {
  const [mode,        setMode]        = useState('login') // 'login' | 'register'
  const [email,       setEmail]       = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password,    setPassword]    = useState('')
  const [error,       setError]       = useState(null)
  const [busy,        setBusy]        = useState(false)

  const isRegister = mode === 'register'

  function switchMode(next) {
    setMode(next)
    setError(null)
  }

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const account = isRegister
        ? await register(email.trim(), displayName.trim(), password)
        : await login(email.trim(), password)
      onAuthed(account)
    } catch (err) {
      setError(friendlyError(err, isRegister))
    } finally {
      setBusy(false)
    }
  }

  const canSubmit =
    email.trim() && password && (!isRegister || displayName.trim())

  return (
    <div className="lobby auth-lobby">
      <h1>Lite-IDE</h1>
      <p className="muted">Sign in to see and edit your workspaces.</p>

      <div className="auth-card">
        <div className="auth-tabs">
          <button
            type="button"
            className={!isRegister ? 'active' : ''}
            onClick={() => switchMode('login')}
          >
            Log in
          </button>
          <button
            type="button"
            className={isRegister ? 'active' : ''}
            onClick={() => switchMode('register')}
          >
            Register
          </button>
        </div>

        <form className="auth-form" onSubmit={submit}>
          <label className="auth-field">
            <span>Email</span>
            <input
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoFocus
            />
          </label>

          {isRegister && (
            <label className="auth-field">
              <span>Display name</span>
              <input
                type="text"
                autoComplete="nickname"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
              />
            </label>
          )}

          <label className="auth-field">
            <span>Password</span>
            <input
              type="password"
              autoComplete={isRegister ? 'new-password' : 'current-password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>

          {error && <div className="error">{error}</div>}

          <button type="submit" disabled={busy || !canSubmit}>
            {busy ? 'Please wait…' : isRegister ? 'Create account' : 'Log in'}
          </button>
        </form>
      </div>
    </div>
  )
}

/** Map backend failures to a short human message. */
function friendlyError(err, isRegister) {
  if (err instanceof ApiError) {
    if (err.status === 409) return 'That email is already registered.'
    if (err.status === 403) return 'Wrong email or password.'
    if (err.status === 400) return err.message || 'Please check the form and try again.'
  }
  return isRegister ? 'Could not create the account. Please try again.' : 'Could not log in. Please try again.'
}

AuthPanel.propTypes = {
  onAuthed: PropTypes.func.isRequired,
}
