# PayPilot AI — Roadmap

Production-grade autonomous AI commerce agent: a Spring Boot backend where
an LLM agent shops on a user's behalf, gated by hard financial guardrails
(purchase-consent FSM, per-purchase and rolling-daily spend caps, audited
tool-call trace), plus a React frontend.

**Prime directive:** the AI can only ever ASK. Money moves exclusively
across explicit human consent, recorded in `agent_sessions.consent_state`.

Schema is law: `V2__core_schema.sql` pre-declares the full design (17
tables); later migrations only activate/extend what each phase needs.

## Shipped

| Phase | Commit | Delivered |
|-------|--------|-----------|
| 0 | — | Planning; 20-phase roadmap |
| 1 | `5407e48` | Spring Boot 3.4 skeleton, Money value type, RFC-7807 errors, correlation-id logging, Flyway, Docker Compose infra, Vite React scaffold |
| 2 | `1810b7c` | Core schema (V2), seeded catalog (32 products / 6 categories / 5 offers), catalog entities, trigram search, schema-contract tests |
| 3 | `77085f9` | Auth: JWT access tokens + rotating hashed refresh tokens, reuse-family revocation, BCrypt, per-IP rate limiting |
| 4 | `4e0c7bf` | Catalog API: trigram search, filters, whitelisted sort, clamped pagination |
| 5 | `8a2c5f4` | Cart: pessimistic-lock mutations, merge-on-add clamps, stock guards, price-drift flag, server-authoritative totals |
| 6 | `3108cb0` | Offers/pricing engine: % and flat with caps/windows/thresholds, per-user redemptions, applied-offer live re-pricing |
| 7 | `5168f45` | Orders/checkout: single-transaction atomic reservation + rollback, offer re-validation, JSONB snapshot, redemption writes |
| 8 | `d834a1a` | Payments: PaymentGatewayPort (mock Razorpay), payment FSM activation, constant-time HMAC webhooks, idempotent capture/failure settlement |
| 9 | `128166e` | Webhook hardening: event-id dedupe ledger, payment.authorized, expiry sweeper, order cancellation vs captures, StockSettlement extraction |
| 10 | `8e8165d` | Agent foundation: audited tool layer over V2 agent tables, consent FSM gate, per-purchase spend cap, mock scripted planner, paged order history |
| 11 | `bc49ada` | Live LLM planner: cursor-style decisions over openai-compatible adapter, human-in-the-loop pause/resume via consent API, runaway-planner ceilings |
| 12 | `4184ec8` | Guardrails completed: consent TTL sweeper (REQUESTED→EXPIRED with audit note), rolling-24h daily spend cap from the customer_events ledger, OpenRouter/Ollama env wiring |
| 13 | `708eb5a` | React frontend: browse/search, cart→checkout→orders, agent chat with audited tool-call trace and consent approve/decline wired to /run + /consent endpoints |
| 14 | `e02a938` | Live Razorpay REST adapter behind PaymentGatewayPort (Basic auth, orders + refunds APIs, outage→GATEWAY_UNAVAILABLE); refund flow: SUCCESS→REFUNDED FSM step, row-lock serialization, persisted gateway refund receipt |
| 15 | `98649d1` | Observability: JSON logging (json-logs profile), Prometheus at /actuator/prometheus with business meters (payments lifecycle, gateway latency, agent tool calls by outcome, consent decisions), correlation ids stamped into audited tool-call rows and surfaced via the transcript API |

## Remaining

| Phase | Scope |
|-------|-------|
| 16 | Security hardening: trusted-proxy rate-limit keys (X-Forwarded-For), CORS for frontend origin, validation sweep |
| 17 | Deployment: multi-stage Dockerfile, compose prod profile, CI pipeline (build + test + image) |
| 18 | Performance: load tests on hot paths (search/cart/checkout/webhook), connection-pool and index tuning |
| 19 | Resilience: gateway outage drills, webhook replay storms, sweeper chaos tests |
| 20 | Launch polish: README/docs rewrite, demo script, seed-data curation |

## Debt ledger (carried until resolved)

- `inventory_reservations` table (V2) still unused — conditional UPDATEs on
  `inventory` serve reservations today.
- Session cumulative spend lives only in `customer_events` (payments carry
  no session FK by schema law).
- Consent TTL anchored to `agent_sessions.updated_at` until a dedicated
  column ever becomes lawful.
- Refunds are full-amount only; partial refunds would need an amounts table
  rather than the single `refund_id` column V7 added.
