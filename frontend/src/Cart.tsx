import { useCallback, useEffect, useState } from 'react'
import { api, getJson, money, postJson } from './api'
import type { Cart, Order } from './types'

export default function CartPanel({
  onCartChanged,
  onCheckedOut,
}: {
  onCartChanged: () => void
  onCheckedOut: () => void
}) {
  const [cart, setCart] = useState<Cart | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [order, setOrder] = useState<Order | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    getJson<Cart>('/api/v1/cart')
      .then(setCart)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to load cart'))
  }, [])

  // Reload whenever the tab becomes visible.
  useEffect(() => {
    load()
  }, [load])

  async function remove(productId: number) {
    setError(null)
    try {
      await api(`/api/v1/cart/items/${productId}`, { method: 'DELETE' })
      load()
      onCartChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not remove item')
    }
  }

  async function checkout() {
    setBusy(true)
    setError(null)
    try {
      const created = await postJson<Order>('/api/v1/orders')
      setOrder(created)
      onCartChanged()
      onCheckedOut()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Checkout failed')
    } finally {
      setBusy(false)
    }
  }

  if (order) {
    return (
      <section>
        <h2>Order confirmed</h2>
        <p className="muted">
          Order #{order.orderId} · {money(order.total)} — pay for it from the
          Agent tab by giving the agent a goal, or wait for a payment link.
        </p>
        <ul className="lines">
          {order.items.map((it) => (
            <li key={it.productId}>
              {it.quantity} × {it.title}{' '}
              <strong>{money(it.lineTotal)}</strong>
            </li>
          ))}
        </ul>
      </section>
    )
  }

  return (
    <section>
      <h2>Your cart</h2>
      {!cart || cart.items.length === 0 ? (
        <p className="muted">Cart is empty — add something from Browse.</p>
      ) : (
        <>
          <ul className="lines">
            {cart.items.map((it) => (
              <li key={it.productId}>
                <div>
                  {it.quantity} × <strong>{it.title}</strong>{' '}
                  {it.priceChanged && (
                    <em className="warn">
                      price changed: was {money(it.addedAtPrice)}, now{' '}
                      {money(it.unitPrice)}
                    </em>
                  )}
                </div>
                <span>
                  {money(it.lineTotal)}{' '}
                  <button
                    className="ghost"
                    onClick={() => remove(it.productId)}
                    title="Remove"
                  >
                    ✕
                  </button>
                </span>
              </li>
            ))}
          </ul>
          <footer className="totals">
            {cart.appliedOfferCode && (
              <p>
                Offer <code>{cart.appliedOfferCode}</code>: −
                {money(cart.discount)}
              </p>
            )}
            <p>
              Total <strong>{money(cart.total)}</strong>
            </p>
            <button onClick={checkout} disabled={busy}>
              {busy ? 'Checking out…' : 'Checkout'}
            </button>
          </footer>
        </>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  )
}
