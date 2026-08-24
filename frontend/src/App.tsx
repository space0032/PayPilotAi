import { useCallback, useState } from 'react'
import AgentChat from './AgentChat'
import AuthPanel from './AuthPanel'
import Cart from './Cart'
import Catalog from './Catalog'
import Orders from './Orders'
import { auth } from './api'

type Tab = 'browse' | 'cart' | 'orders' | 'agent'

export default function App() {
  const [tab, setTab] = useState<Tab>('agent')
  const [cartTick, setCartTick] = useState(0)
  const bumpCart = useCallback(() => setCartTick((t) => t + 1), [])

  if (!auth.loggedIn) {
    return (
      <main className="shell">
        <header>
          <h1>PayPilot</h1>
          <p className="muted">An AI agent that shops for you — with your permission.</p>
        </header>
        <AuthPanel />
      </main>
    )
  }

  return (
    <main className="shell">
      <header className="topbar">
        <h1>PayPilot</h1>
        <nav>
          {(['agent', 'browse', 'cart', 'orders'] as const).map((t) => (
            <button
              key={t}
              className={`tab ${tab === t ? 'active' : ''}`}
              onClick={() => setTab(t)}
            >
              {t === 'agent'
                ? '🤖 Agent'
                : t === 'browse'
                  ? 'Browse'
                  : t === 'cart'
                    ? `Cart${cartTick > 0 ? '' : ''}`
                    : 'Orders'}
            </button>
          ))}
        </nav>
        <button
          className="ghost"
          onClick={() => {
            auth.clear()
            window.location.reload()
          }}
        >
          Sign out
        </button>
      </header>

      {tab === 'agent' && <AgentChat />}
      {tab === 'browse' && <Catalog onCartChanged={bumpCart} />}
      {tab === 'cart' && (
        <Cart key={cartTick} onCartChanged={bumpCart} onCheckedOut={bumpCart} />
      )}
      {tab === 'orders' && <Orders key={cartTick} />}
    </main>
  )
}
