# PayPilot AI

**An autonomous AI commerce agent with hard financial guardrails.**

PayPilot lets an LLM agent shop on your behalf — browse a catalog, manage your cart, negotiate offers, and check you out — while ensuring **money only moves when you explicitly approve it**. The consent state machine, spend caps, and full audit trail are enforced at the database level; the model itself cannot bypass them.

```
┌─────────────────────────────────────────────────────────────┐
│                        React 19 UI                         │
│   Catalog · Cart · Orders · Agent Chat (consent banner)    │
└─────────────────────────┬───────────────────────────────────┘
                          │  REST / JWT
┌─────────────────────────▼───────────────────────────────────┐
│                    Spring Boot 3.4                           │
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐  ┌───────────┐ │
│  │ Catalog  │  │  Cart +  │  │ Payments  │  │  Agent    │ │
│  │ + Search │  │ Checkout │  │ (Gateway) │  │  (LLM)   │ │
│  └────┬─────┘  └────┬─────┘  └─────┬─────┘  └─────┬─────┘ │
│       │              │              │              │        │
│  ┌────▼──────────────▼──────────────▼──────────────▼─────┐  │
│  │           PostgreSQL 16  (Flyway V1..V10)            │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Key Features

| Feature | What it does |
|---------|-------------|
| **AI Agent** | LLM-driven shopping assistant with tool-call loop: searches products, adds to cart, applies offers, initiates checkout |
| **Consent FSM** | `NONE → REQUESTED → CONFIRMED → CONSUMED` (+ `EXPIRED` / `CANCELLED`). Payment requires `CONFIRMED`; one approval = exactly one purchase |
| **Spend Caps** | Per-purchase cap (default ₹10,000) + rolling 24h daily cap (default ₹20,000) from the `customer_events` ledger |
| **Full Audit** | Every tool call persisted with arguments, result, status (OK/ERROR/REJECTED), duration, and correlation ID |
| **Payments** | Razorpay gateway adapter (mock + live), HMAC webhook verification, idempotent event ledger, stock settlement on capture/failure/expiry |
| **Multi-Currency** | ISO 4217 currency columns on products/orders; pluggable `CurrencyConverter` with env-configurable rates |
| **Observability** | JSON structured logs, Prometheus metrics (`/actuator/prometheus`), business meters for payments/agent/consent |
| **Security** | CIDR-aware trusted-proxy IP resolution, CORS policy, per-IP rate limiting, BCrypt + JWT with rotating refresh tokens |
| **Frontend** | React 19 + Vite + TypeScript; renders every agent tool call as an auditable trace row with approve/decline banner |

## Guardrails

The agent can only ever **ask**. Money moves exclusively across explicit human consent.

| Guardrail | Mechanism |
|-----------|-----------|
| Purchase consent | `agent_sessions.consent_state` FSM — `NONE → REQUESTED → CONFIRMED → CONSUMED`, plus terminal `EXPIRED` / `CANCELLED`. Payment initiation requires `CONFIRMED` and consumes the grant. |
| Per-purchase spend cap | Checkout refuses before any mutation when cart total exceeds `AGENT_MAX_SPEND_PAISE` (default ₹10,000). |
| Rolling daily cap | 24h ceiling across all sessions, summed from `customer_events` (`AGENT_DAILY_SPEND_CAP_PAISE`, default ₹20,000). |
| Consent TTL | Unanswered approval asks expire after `AGENT_CONSENT_TTL_MINUTES` (default 30 min) via a reconciliation sweep. |
| Full audit trail | Every tool call persisted with arguments, result summary, OK/REJECTED/ERROR status and duration. |

## Quick Start

### Docker (recommended)

```bash
# Clone and configure
git clone https://github.com/<your-org>/PayPilotAi.git
cd PayPilotAi
cp .env.example .env    # edit: JWT_SECRET, Razorpay keys

# Build and run everything
docker compose --profile prod up -d --build

# The app is now at http://localhost:8080
```

### Local Development

```powershell
# 1. Infrastructure (Postgres :5432, Redis :6379)
docker compose up -d

# 2. Configure
Copy-Item .env.example .env   # fill JWT_SECRET etc.

# 3. Backend (:8080)
cd backend && mvn spring-boot:run

# 4. Frontend (:5173, proxies /api to :8080)
cd frontend && npm install && npm run dev
```

## Environment Variables

See `.env.example` for the full list. Key variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET` | — | HS256 signing key (generate once, keep stable) |
| `AGENT_PLANNER` | `mock` | `mock` (scripted) or `live` (LLM-driven) |
| `AGENT_LLM_PROVIDER` | `none` | `none` or `openai-compatible` |
| `AGENT_LLM_BASE_URL` | — | OpenAI-compatible endpoint URL |
| `AGENT_LLM_API_KEY` | — | API key (falls back to `OPENROUTER_*` → `OLLAMA_*`) |
| `AGENT_LLM_MODEL` | `gpt-4o-mini` | Model name |
| `AGENT_MAX_SPEND_PAISE` | `1000000` | Per-purchase cap in paise (₹10,000) |
| `AGENT_DAILY_SPEND_CAP_PAISE` | `2000000` | Rolling 24h cap in paise (₹20,000) |
| `PAYMENTS_GATEWAY` | `mock` | `mock` or `razorpay` |
| `RAZORPAY_KEY_ID` | — | Razorpay test/live key |
| `RAZORPAY_KEY_SECRET` | — | Razorpay test/live secret |
| `CURRENCY_RATES` | `{}` | JSON map of rates-per-INR (e.g. `{"USD":0.012}`) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated allowed origins |
| `TRUSTED_PROXY_CIDRS` | — | Comma-separated CIDRs for XFF trust |

## API Reference

All endpoints require a JWT Bearer token except `/auth/register`, `/auth/login`, and `/products`.

### Auth
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/register` | Register (returns accessToken + refreshToken) |
| POST | `/api/v1/auth/login` | Login |
| POST | `/api/v1/auth/refresh` | Rotate refresh token |

### Catalog
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/products?q=&category=&currency=&page=&size=` | Search/list (trigram search, `?currency=USD` for conversion) |
| GET | `/api/v1/products/{sku}` | Product detail |
| GET | `/api/v1/categories` | List categories |

### Cart
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/cart` | View cart |
| POST | `/api/v1/cart/items` | Add item `{productId, quantity}` |
| PATCH | `/api/v1/cart/items/{productId}` | Update quantity `{quantity}` |
| DELETE | `/api/v1/cart/items/{productId}` | Remove item |
| POST | `/api/v1/cart/offers` | Apply offer `{code}` |
| DELETE | `/api/v1/cart/offers` | Remove offer |

### Orders
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/orders` | Checkout (atomic: reserve stock + create order + payment) |
| GET | `/api/v1/orders` | Order history (paginated) |
| GET | `/api/v1/orders/{orderId}` | Order detail |
| POST | `/api/v1/orders/{orderId}/cancel` | Cancel unpaid order |

### Payments
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/payments` | Initiate payment for order |
| POST | `/api/v1/payments/webhook` | Razorpay webhook (HMAC-verified) |
| POST | `/api/v1/payments/{paymentId}/refund` | Full refund |

### Agent
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/agent/sessions` | Start session `{goal}` |
| POST | `/api/v1/agent/sessions/{id}/run` | Resume planner loop |
| POST | `/api/v1/agent/sessions/{id}/consent/confirm` | Approve spend |
| POST | `/api/v1/agent/sessions/{id}/consent/cancel` | Decline spend |
| GET | `/api/v1/agent/sessions/{id}` | Session transcript (messages + tool calls) |

### Observability
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Prometheus metrics |

## Agent Architecture

The agent runs a **cursor-style planner loop**: each `run` invocation sends the current context to the LLM (or mock script), receives a tool call, executes it, records the result, and loops until the planner emits `{"tool":"done"}` or hits `MAX_STEPS_PER_RUN` (20).

```
User → POST /agent/sessions {goal: "buy running shoes"}
  ↓
Planner → {tool: "search_products", arguments: {term: "running shoes"}}
  ↓ AgentTools.search()
Planner → {tool: "add_to_cart", arguments: {productId: 1}}
  ↓ AgentTools.addToCart()
Planner → {tool: "request_consent", arguments: {amountPaise: 369900}}
  ↓ consent_state: NONE → REQUESTED (pause loop)
  ↓
User → POST /consent/confirm
  ↓ consent_state: REQUESTED → CONFIRMED
  ↓
User → POST /run (resume)
  ↓
Planner → {tool: "checkout"}
  ↓ consent_state: CONFIRMED → CONSUMED
  ↓
Planner → {tool: "done"}
```

The agent physically has no "approve spend" tool — it can only *request*. The consent endpoints are human-only actions on the REST API.

## Testing

```powershell
cd backend && mvn test
```

126 tests across 18 suites. Most are Testcontainers integration tests
(Docker required); unit suites (`MoneyTest`, `PricingEngineTest`,
`JwtServiceTest`, `TrustedProxyResolverTest`, `InMemoryCurrencyConverterTest`)
run anywhere.

`RateLimitIntegrationTest`, `SpoofedForwardedForTest`, and
`TrustedProxyIntegrationTest` target the compose Postgres on
`localhost:5432` — bring the stack up first.

### Load Tests

`PerformanceIntegrationTest` uses Java 21 virtual threads to measure
p50/p95/max latencies on four hot paths:

| Path | Concurrency | p95 ceiling |
|------|-------------|-------------|
| Search (trigram) | 20 | < 2s |
| Cart add (pessimistic lock) | 10 | < 3s |
| Checkout (atomic reservation) | 5 | < 5s |
| Agent start (mock planner) | 5 | < 15s |

## Project Layout

```
backend/src/main/java/com/paypilot/
  common/       Money value type, CurrencyConverter, error envelope, clock, scheduling
  security/     auth, JWT, refresh rotation, rate limiting, CORS, trusted-proxy
  commerce/     catalog, cart, offers, orders, payments
  agent/        planner port(s), tool layer, guardrails, audit
  config/       scheduling, observability
frontend/src/   App shell + AuthPanel / Catalog / Cart / Orders / AgentChat
backend/src/main/resources/db/migration/
  V1..V10 Flyway  (V2 is the schema law; V3 seeds 32 products / 5 offers)
```

## Deployment

See [ROADMAP.md](ROADMAP.md) for phase history (0–19 shipped).

**Production Docker image** (~140 MB):
```bash
docker compose --profile prod up -d --build
```

**CI/CD** (GitHub Actions):
- Every push to `main` and every PR runs the full test suite.
- On merge to `main`, a versioned Docker image is built and pushed to
  `ghcr.io/<repo>` (tagged by commit SHA + `latest`).

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 3.4, Spring Data JPA, Spring Security |
| Database | PostgreSQL 16 (Flyway migrations, JSONB, trigram search) |
| Cache | Redis 7 (rate limiting) |
| Auth | JWT (HS256) + BCrypt + rotating refresh tokens |
| Payments | Razorpay REST API (mock + live adapters) |
| Agent | OpenAI-compatible chat API (OpenRouter, Ollama, OpenAI) |
| Frontend | React 19, Vite, TypeScript |
| Build | Maven, Docker (multi-stage), GitHub Actions |
| Testing | JUnit 5, Testcontainers, AssertJ |

## License

MIT
