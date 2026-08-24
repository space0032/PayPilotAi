import type { AuthResponse } from './types'

const TOKEN_KEY = 'pp_access'
const REFRESH_KEY = 'pp_refresh'

export class ApiError extends Error {
  readonly status: number
  readonly code: string | null

  constructor(status: number, code: string | null, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export const auth = {
  get token(): string | null {
    return localStorage.getItem(TOKEN_KEY)
  },
  save(grant: AuthResponse) {
    localStorage.setItem(TOKEN_KEY, grant.accessToken)
    localStorage.setItem(REFRESH_KEY, grant.refreshToken)
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
  get loggedIn(): boolean {
    return !!localStorage.getItem(TOKEN_KEY)
  },
}

/** RFC-7807 problem body, plus our custom `code` where present. */
async function toApiError(response: Response): Promise<ApiError> {
  let code: string | null = null
  let message = `${response.status} ${response.statusText}`
  try {
    const body = await response.json()
    code = (body as { code?: string }).code ?? null
    message =
      (body as { detail?: string }).detail ??
      (body as { title?: string }).title ??
      message
  } catch {
    // non-JSON error body; fall back to status text
  }
  return new ApiError(response.status, code, message)
}

let refreshing: Promise<boolean> | null = null

async function tryRefresh(): Promise<boolean> {
  const refreshToken = localStorage.getItem(REFRESH_KEY)
  if (!refreshToken) return false
  // Single-flight: parallel 401s share one refresh call.
  refreshing ??= fetch('/api/v1/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  })
    .then(async (r) => {
      if (!r.ok) return false
      const grant = (await r.json()) as AuthResponse
      auth.save(grant)
      return true
    })
    .catch(() => false)
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

export async function api<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers)
  if (!headers.has('Content-Type') && init.body != null) {
    headers.set('Content-Type', 'application/json')
  }
  const token = auth.token
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response = await fetch(path, { ...init, headers })
  if (response.status === 401 && (await tryRefresh())) {
    headers.set('Authorization', `Bearer ${auth.token}`)
    response = await fetch(path, { ...init, headers })
  }
  if (!response.ok) throw await toApiError(response)
  return (await response.json()) as T
}

export const postJson = <T>(path: string, body?: unknown): Promise<T> =>
  api<T>(path, { method: 'POST', body: body == null ? undefined : JSON.stringify(body) })

export const getJson = <T>(path: string): Promise<T> => api<T>(path)

export function money(value: number | string | null | undefined): string {
  const n = typeof value === 'string' ? Number(value) : value
  return n == null || Number.isNaN(n)
    ? '—'
    : n.toLocaleString('en-IN', { style: 'currency', currency: 'INR' })
}
