// Mirrors of the backend API contracts (Phase 2-12 DTOs).

export interface Product {
  id: number
  sku: string
  brand: string
  title: string
  price: number | string
  rating: number | string | null
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export interface CartItem {
  productId: number
  sku: string
  brand: string
  title: string
  quantity: number
  available: number
  unitPrice: number | string
  lineTotal: number | string
  priceChanged: boolean
  addedAtPrice: number | string
}

export interface Cart {
  cartId: number
  items: CartItem[]
  totalItems: number
  subtotal: number | string
  appliedOfferCode: string | null
  discount: number | string
  total: number | string
}

export interface OrderItem {
  productId: number
  sku: string
  brand: string
  title: string
  quantity: number
  unitPrice: number | string
  lineTotal: number | string
}

export interface Order {
  orderId: number
  status: string
  items: OrderItem[]
  subtotal: number | string
  discount: number | string
  total: number | string
  createdAt: string
}

export interface OrderSummary {
  orderId: number
  status: string
  total: number | string
  createdAt: string
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  userId: number
  email: string
  role: string
}

export interface ToolCall {
  tool: string
  arguments: Record<string, unknown> | null
  resultSummary: Record<string, unknown> | null
  status: 'OK' | 'ERROR' | 'REJECTED'
  error: string | null
}

export interface ChatMessage {
  role: 'USER' | 'AGENT' | 'SYSTEM' | 'TOOL'
  content: string
}

export interface Transcript {
  sessionId: number
  title: string
  consentState:
    | 'NONE'
    | 'REQUESTED'
    | 'CONFIRMED'
    | 'CONSUMED'
    | 'EXPIRED'
    | 'CANCELLED'
  reservedSpend: number | string
  toolCalls: ToolCall[]
  messages: ChatMessage[]
}
