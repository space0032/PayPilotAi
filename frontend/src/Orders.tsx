import { useCallback, useEffect, useState } from 'react'
import { getJson, money } from './api'
import type { OrderSummary } from './types'

export default function Orders() {
  const [orders, setOrders] = useState<OrderSummary[]>([])
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    getJson<{ items: OrderSummary[]; totalElements: number }>(
      '/api/v1/orders?page=0&size=20',
    )
      .then((page) => setOrders(page.items))
      .catch((e) =>
        setError(e instanceof Error ? e.message : 'Failed to load orders'),
      )
  }, [])

  useEffect(() => {
    load()
  }, [load])

  return (
    <section>
      <h2>Order history</h2>
      {error && <p className="error">{error}</p>}
      {orders.length === 0 ? (
        <p className="muted">No orders yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Order</th>
              <th>Status</th>
              <th>Total</th>
              <th>Placed</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((o) => (
              <tr key={o.orderId}>
                <td>#{o.orderId}</td>
                <td>
                  <span className={`badge ${o.status.toLowerCase()}`}>
                    {o.status}
                  </span>
                </td>
                <td>{money(o.total)}</td>
                <td className="muted">
                  {new Date(o.createdAt).toLocaleString()}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
