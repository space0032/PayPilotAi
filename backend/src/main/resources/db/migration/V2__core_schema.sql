-- =============================================================================
-- PayPilot AI — core relational schema (Phase 2 baseline)
--
-- Conventions:
--   * Money is BIGINT paise, never floating point.
--   * Status columns are VARCHAR + CHECK (documented in-schema, easy to evolve).
--   * ids are BIGINT GENERATED ALWAYS AS IDENTITY (compact, fast joins).
--   * created_at/updated_at default to now(); Hibernate also fills them.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Identity
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));

-- -----------------------------------------------------------------------------
-- Catalog
-- -----------------------------------------------------------------------------
CREATE TABLE product_categories (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(140) NOT NULL
);
CREATE UNIQUE INDEX uq_categories_slug ON product_categories (slug);

CREATE TABLE products (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id BIGINT       NOT NULL REFERENCES product_categories(id),
    sku         VARCHAR(64)  NOT NULL,
    brand       VARCHAR(80)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    price_paise BIGINT       NOT NULL CHECK (price_paise >= 0),
    rating      NUMERIC(3,2) CHECK (rating BETWEEN 0 AND 5),
    attributes  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_products_sku     ON products (sku);
CREATE INDEX        idx_products_cat    ON products (category_id);
CREATE INDEX        idx_products_price  ON products (price_paise) WHERE active;
CREATE INDEX        idx_products_title_trgm ON products USING gin (title gin_trgm_ops);

-- Stock truth for the whole system. Reservation math happens here (atomic
-- conditional UPDATEs added with cart/checkout phases).
CREATE TABLE inventory (
    product_id BIGINT PRIMARY KEY REFERENCES products(id),
    available  INT NOT NULL DEFAULT 0 CHECK (available >= 0),
    reserved   INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- Cart — exactly one ACTIVE cart per user (partial unique index)
-- -----------------------------------------------------------------------------
CREATE TABLE carts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
               CHECK (status IN ('ACTIVE','ORDERED','ABANDONED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_carts_one_active_per_user ON carts (user_id) WHERE status = 'ACTIVE';

CREATE TABLE cart_items (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_id              BIGINT NOT NULL REFERENCES carts(id),
    product_id           BIGINT NOT NULL REFERENCES products(id),
    quantity             INT    NOT NULL CHECK (quantity > 0 AND quantity <= 10),
    price_snapshot_paise BIGINT NOT NULL CHECK (price_snapshot_paise >= 0),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cart_items_cart_product UNIQUE (cart_id, product_id)
);

-- -----------------------------------------------------------------------------
-- Offers & redemptions
-- discount_value units depend on type:
--   PERCENTAGE -> basis points (1500 = 15%), bounded 1..10000 by CHECK
--   FLAT       -> paise
-- -----------------------------------------------------------------------------
CREATE TABLE offers (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                 VARCHAR(40) NOT NULL,
    type                 VARCHAR(20) NOT NULL CHECK (type IN ('PERCENTAGE','FLAT')),
    discount_value       BIGINT      NOT NULL,
    max_discount_paise   BIGINT      CHECK (max_discount_paise IS NULL OR max_discount_paise >= 0),
    min_cart_paise       BIGINT      NOT NULL DEFAULT 0 CHECK (min_cart_paise >= 0),
    valid_from           TIMESTAMPTZ,
    valid_to             TIMESTAMPTZ,
    usage_limit_per_user INT         NOT NULL DEFAULT 1 CHECK (usage_limit_per_user >= 1),
    active               BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_offers_code UNIQUE (code),
    CONSTRAINT chk_offer_value CHECK (
        (type = 'PERCENTAGE' AND discount_value BETWEEN 1 AND 10000)
        OR (type = 'FLAT' AND discount_value >= 1)
    )
);

-- -----------------------------------------------------------------------------
-- Orders — stable purchase intent; payments below are disposable attempts
-- -----------------------------------------------------------------------------
CREATE TABLE orders (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users(id),
    status         VARCHAR(30) NOT NULL DEFAULT 'PENDING_PAYMENT'
                   CHECK (status IN ('PENDING_PAYMENT','CONFIRMED','CANCELLED')),
    subtotal_paise BIGINT      NOT NULL CHECK (subtotal_paise >= 0),
    discount_paise BIGINT      NOT NULL DEFAULT 0 CHECK (discount_paise >= 0),
    total_paise    BIGINT      NOT NULL CHECK (total_paise >= 0),
    offer_id       BIGINT      REFERENCES offers(id),
    cart_snapshot  JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_order_total_math CHECK (total_paise = subtotal_paise - discount_paise)
);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);

CREATE TABLE order_items (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id         BIGINT NOT NULL REFERENCES orders(id),
    product_id       BIGINT NOT NULL REFERENCES products(id),
    quantity         INT    NOT NULL CHECK (quantity > 0),
    unit_price_paise BIGINT NOT NULL CHECK (unit_price_paise >= 0)
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE offer_redemptions (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    offer_id       BIGINT NOT NULL REFERENCES offers(id),
    user_id        BIGINT NOT NULL REFERENCES users(id),
    order_id       BIGINT NOT NULL REFERENCES orders(id),
    discount_paise BIGINT NOT NULL CHECK (discount_paise >= 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_redemptions_offer_order UNIQUE (offer_id, order_id)
);
CREATE INDEX idx_redemptions_offer_user ON offer_redemptions (offer_id, user_id);

-- -----------------------------------------------------------------------------
-- Payments — N attempts per order; amount immutable once created
-- -----------------------------------------------------------------------------
CREATE TABLE payments (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id            BIGINT      NOT NULL REFERENCES orders(id),
    razorpay_order_id   VARCHAR(64) UNIQUE,
    razorpay_payment_id VARCHAR(64) UNIQUE,
    amount_paise        BIGINT      NOT NULL CHECK (amount_paise >= 0),
    currency            CHAR(3)     NOT NULL DEFAULT 'INR',
    status              VARCHAR(30) NOT NULL DEFAULT 'CREATED' CHECK (status IN (
                        'CREATED','PAYMENT_PENDING','AUTHORIZED','PROCESSING',
                        'SUCCESS','FAILED','EXPIRED','CANCELLED')),
    failure_reason      VARCHAR(255),
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_status_created ON payments (status, created_at);

-- Append-only audit ledger. event_id carries the gateway's webhook event id;
-- UNIQUE on it makes duplicate webhooks a constraint violation => idempotent.
CREATE TABLE payment_events (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id         BIGINT      NOT NULL REFERENCES payments(id),
    event_id           VARCHAR(80),
    type               VARCHAR(60) NOT NULL,
    payload            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    signature_verified BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_events_event_id UNIQUE (event_id)
);
CREATE INDEX idx_payment_events_payment ON payment_events (payment_id, created_at);

-- Stock held while a payment attempt is in flight; released on failure/expiry.
CREATE TABLE inventory_reservations (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments(id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity   INT    NOT NULL CHECK (quantity > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    released   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservations_open_expiry ON inventory_reservations (expires_at) WHERE released = FALSE;

-- -----------------------------------------------------------------------------
-- Agent sessions / messages / tool-call trace / analytics
-- consent_state implements the purchase-authorization gate (guardrail):
-- NONE -> REQUESTED -> CONFIRMED -> CONSUMED, plus EXPIRED/CANCELLED paths.
-- -----------------------------------------------------------------------------
CREATE TABLE agent_sessions (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users(id),
    title         VARCHAR(200),
    consent_state VARCHAR(20) NOT NULL DEFAULT 'NONE'
                  CHECK (consent_state IN ('NONE','REQUESTED','CONFIRMED','CONSUMED','EXPIRED','CANCELLED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_sessions_user ON agent_sessions (user_id, created_at DESC);

CREATE TABLE agent_messages (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  BIGINT      NOT NULL REFERENCES agent_sessions(id),
    role        VARCHAR(12) NOT NULL CHECK (role IN ('USER','AGENT','SYSTEM','TOOL')),
    content     TEXT        NOT NULL,
    token_usage INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_messages_session ON agent_messages (session_id, created_at);

CREATE TABLE agent_tool_calls (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id     BIGINT      NOT NULL REFERENCES agent_sessions(id),
    tool           VARCHAR(60) NOT NULL,
    arguments      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    result_summary JSONB,
    status         VARCHAR(20) NOT NULL CHECK (status IN ('OK','ERROR','REJECTED')),
    error          TEXT,
    duration_ms    INT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_tool_calls_session ON agent_tool_calls (session_id, created_at);

CREATE TABLE customer_events (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT REFERENCES users(id),
    session_id BIGINT REFERENCES agent_sessions(id),
    type       VARCHAR(60) NOT NULL,
    payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_events_user ON customer_events (user_id, created_at DESC);
