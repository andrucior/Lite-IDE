import { useEffect, useState } from 'react'
import CodeEditor from './CodeEditor.jsx'
import DocumentList from './DocumentList.jsx'
import AuthPanel from './AuthPanel.jsx'
import { me, setOnUnauthorized } from './api.js'

/**
 * Top-level shell.
 *
 * Three screens, chosen by session + navigation state:
 *   - no session  → `AuthPanel` (login / register)
 *   - signed in   → `DocumentList` (the user's own + shared workspaces)
 *   - a doc open  → `CodeEditor`
 *
 * The session is resolved once on mount via `/api/auth/me`: `account === undefined`
 * means "still checking", `null` means "logged out". Everything below the auth gate can
 * assume a real `account` (`{ id, email, displayName }`).
 */
export default function App() {
  const [account, setAccount] = useState(undefined) // undefined = checking, null = logged out
  const [doc, setDoc] = useState(null)

  useEffect(() => {
    me().then(setAccount).catch(() => setAccount(null))
  }, [])

  // Any authenticated request that comes back 401 (session expired, cookie cleared) drops
  // us straight to the login screen.
  useEffect(() => {
    setOnUnauthorized(() => { setDoc(null); setAccount(null) })
    return () => setOnUnauthorized(null)
  }, [])

  if (account === undefined) {
    return <div className="loading">Loading…</div>
  }

  if (account === null) {
    return <AuthPanel onAuthed={setAccount} />
  }

  if (doc) {
    return (
      <CodeEditor
        document={doc}
        account={account}
        onBack={() => setDoc(null)}
      />
    )
  }

  return (
    <DocumentList
      account={account}
      onOpen={setDoc}
      onLoggedOut={() => { setDoc(null); setAccount(null) }}
    />
  )
}
