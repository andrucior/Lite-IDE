import { useEffect, useState } from 'react'
import CodeEditor from './CodeEditor.jsx'
import DocumentList from './DocumentList.jsx'

/**
 * Top-level shell.
 *
 * The app has two screens — a document list, and the editor for one document. We keep
 * the user name in localStorage so reconnects after a tab refresh keep the same display
 * name (the backend invents one when missing, but having a stable name across sessions
 * is what users expect).
 */
export default function App() {
  const [doc, setDoc]   = useState(null)
  const [user, setUser] = useState(() => localStorage.getItem('lite-ide-user') ?? '')

  useEffect(() => {
    if (user) localStorage.setItem('lite-ide-user', user)
  }, [user])

  if (!user) {
    return (
      <div className="lobby">
        <h1>Lite-IDE</h1>
        <p className="muted">Pick a display name to start collaborating.</p>
        <NameForm onSubmit={setUser} />
      </div>
    )
  }

  if (doc) return <CodeEditor document={doc} userName={user} onBack={() => setDoc(null)} />

  return <DocumentList onOpen={setDoc} />
}

function NameForm({ onSubmit }) {
  const [name, setName] = useState('')
  return (
    <form
      className="create-row"
      onSubmit={(e) => { e.preventDefault(); if (name.trim()) onSubmit(name.trim()) }}
    >
      <input placeholder="your name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
      <button type="submit" disabled={!name.trim()}>Continue</button>
    </form>
  )
}
