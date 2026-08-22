import { useEffect, useState } from 'react'

interface Health {
  status: string
}

export default function App() {
  const [backendStatus, setBackendStatus] = useState<string>('checking…')

  useEffect(() => {
    fetch('/actuator/health')
      .then((r) => r.json())
      .then((h: Health) => setBackendStatus(h.status))
      .catch(() => setBackendStatus('unreachable'))
  }, [])

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', maxWidth: 720, margin: '4rem auto', padding: '0 1rem' }}>
      <h1>PayPilot AI</h1>
      <p>Autonomous commerce agent — scaffold placeholder (UI arrives in later phases).</p>
      <p>
        Backend health: <strong data-testid="backend-status">{backendStatus}</strong>
      </p>
    </main>
  )
}
