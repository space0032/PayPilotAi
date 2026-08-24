import { useCallback, useEffect, useState } from 'react'
import { getJson, money, postJson } from './api'
import type { PageResponse, Product } from './types'

export default function Catalog({ onCartChanged }: { onCartChanged: () => void }) {
  const [term, setTerm] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<PageResponse<Product> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [adding, setAdding] = useState<number | null>(null)

  const load = useCallback((t: string, p: number) => {
    const params = new URLSearchParams({ page: String(p), size: '12', sort: 'price_asc' })
    if (t.trim()) params.set('term', t.trim())
    getJson<PageResponse<Product>>(`/api/v1/products?${params}`)
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to load'))
  }, [])

  useEffect(() => {
    load(term, page)
  }, [load, term, page])

  async function addToCart(productId: number) {
    setAdding(productId)
    setError(null)
    try {
      await postJson('/api/v1/cart/items', { productId, quantity: 1 })
      onCartChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not add to cart')
    } finally {
      setAdding(null)
    }
  }

  return (
    <section>
      <div className="toolbar">
        <input
          className="search"
          placeholder="Search products…"
          value={term}
          onChange={(e) => {
            setPage(0)
            setTerm(e.target.value)
          }}
        />
      </div>
      {error && <p className="error">{error}</p>}
      <div className="grid">
        {(data?.items ?? []).map((p) => (
          <article key={p.id} className="card">
            <span className="brand">{p.brand}</span>
            <h3>{p.title}</h3>
            <footer>
              <strong>{money(p.price)}</strong>
              <button
                disabled={adding === p.id}
                onClick={() => addToCart(p.id)}
              >
                {adding === p.id ? 'Adding…' : 'Add to cart'}
              </button>
            </footer>
          </article>
        ))}
        {data && data.items.length === 0 && (
          <p className="muted">No products match “{term}”.</p>
        )}
      </div>
      {data && data.totalPages > 1 && (
        <nav className="pager">
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>
            ← Prev
          </button>
          <span className="muted">
            Page {page + 1} of {data.totalPages}
          </span>
          <button
            disabled={!data.hasNext}
            onClick={() => setPage(page + 1)}
          >
            Next →
          </button>
        </nav>
      )}
    </section>
  )
}
