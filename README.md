# PayPilot AI

An AI shopping agent with financial guardrails you can actually trust.
PayPilot lets an LLM agent browse a catalog, manage your cart and check
you out on your behalf — but **money only moves when you explicitly
approve**, through a purchase-consent state machine the model itself
cannot bypass.

## How the guardrails work

| Guardrail | Mechanism |
|-----------|-----------|
| Purchase consent | `agent_sessions.consent_state` FSM: `NONE → REQUESTED → CONFIRMED → CONSUMED`, plus terminal `EXPIRED`/`CANCELLED`. Payment initiation requires `CONFIRMED` and consumes the grant — one approval, exactly one purchase. |
| Per-purchase spend cap | Checkout refuses before any mutation when the cart total exceeds `AGENT_MAX_SPEND_PAISE` (default ₹10,000). |
| Rolling daily cap | A 24-hour ceiling across all of a user's sessions, summed from the `customer_events` spend ledger (`AGENT_DAILY_SPEND_CAP_PAISE`, default ₹20,000). |
| Consent TTL | Unanswered approval asks expire after `AGENT_CONSENT_TTL_MINUTES` (default 30 min) via a reconciliation sweep. |
| Full audit trail | Every tool call is persisted with arguments, result summary, OK/REJECTED/ERROR status and duration. Failures end runs as transcript data, never HTTP 500s. |

The agent can only ever *ask* — approving is a human action on the
session API (`/consent/confirm`, `/consent/cancel`). In mock-planner mode
the planner plays the human; in live mode it physically has no such tool.

## Stack

- **Backend** — Spring Boot 3.4 (Java), PostgreSQL 16 (Flyway migrations,
  JSONB audit payloads), Redis (rate limiting), JWT auth with rotating
  refresh tokens, RFC-7807 errors.
- **Agent** — `AgentPlanner` port with two implementations:
  - `mock` (default): deterministic scripted journey, no account needed.
  - `live`: any OpenAI-compatible chat endpoint (OpenRouter, Ollama,
    OpenAI) over plain `java.net.http` — no SDK.
- **Payments** — `PaymentGatewayPort` with a mock Razorpay adapter
  (signed webhooks, idempotent event ledger, stock settlement on
  capture/failure/expiry).
- **Frontend** — React 19 + Vite + TypeScript, zero runtime deps beyond
  React. The agent tab renders every tool call as an auditable trace row
  and raises an approve-or-decline banner whenever the agent asks to
  spend.

## Running locally

```powershell
# infra (Postgres :5432, Redis :6372)
docker compose up -d

# configure
Copy-Item .env.example .env   # then fill JWT_SECRET etc.

# backend (:8080)
cd backend && ./mvnw spring-boot:run

# frontend (:5173, proxies /api to :8080)
cd frontend && npm install && npm run dev
```

### Environment

See `.env.example`. Highlights:

- `JWT_SECRET` — HS256 signing key (generate once, keep stable).
- `AGENT_PLANNER` — `mock` | `live`.
- `AGENT_LLM_PROVIDER` — `none` | `openai-compatible`; credentials fall
  back `AGENT_LLM_* → OPENROUTER_* → OLLAMA_*`.

## Testing

```powershell
cd backend && mvn test
```

91 tests across 14 suites. Most are Testcontainers integration tests
(Docker required); unit suites (`MoneyTest`, `PricingEngineTest`,
`JwtServiceTest`) run anywhere. `RateLimitIntegrationTest` targets the
compose Postgres on `localhost:5432`, so bring the stack up first.

## Project layout

```
backend/src/main/java/com/paypilot/
  common/       Money value type, error envelope, clock, scheduling
  security/     auth, JWT, refresh rotation, rate limiting
  commerce/     catalog, cart, offers, orders, payments
  agent/        planner port(s), tool layer, guardrails, audit
frontend/src/   App shell + AuthPanel/Catalog/Cart/Orders/AgentChat
db/migration/   V1..V6 Flyway (V2 is the schema law everything maps to)
```

See [ROADMAP.md](ROADMAP.md) for phase history (0–13 shipped) and what's
next.
