import { useState } from 'react'
import { auth, postJson } from './api'
import type { AuthResponse } from './types'

export default function AuthPanel() {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const grant = await postJson<AuthResponse>(
        mode === 'login' ? '/api/v1/auth/login' : '/api/v1/auth/register',
        { email, password },
      )
      auth.save(grant)
      window.location.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong')
      setBusy(false)
    }
  }

  return (
    <div className="auth-panel">
      <h2>{mode === 'login' ? 'Sign in' : 'Create account'}</h2>
      <form onSubmit={submit}>
        <input
          type="email"
          placeholder="you@example.com"
          value={email}
          required
          onChange={(e) => setEmail(e.target.value)}
        />
        <input
          type="password"
          placeholder="Password"
          value={password}
          required
          minLength={8}
          onChange={(e) => setPassword(e.target.value)}
        />
        <button type="submit" disabled={busy}>
          {busy ? 'Working…' : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>
      </form>
      {error && <p className="error">{error}</p>}
      <p className="switch">
        {mode === 'login' ? 'No account yet? ' : 'Already registered? '}
        <a
          href="#"
          onClick={(e) => {
            e.preventDefault()
            setMode(mode === 'login' ? 'register' : 'login')
            setError(null)
          }}
        >
          {mode === 'login' ? 'Create one' : 'Sign in'}
        </a>
      </p>
    </div>
  )
}
