-- Stock for each product. One row per product.
CREATE TABLE inventory (
    product_id BIGINT  PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    -- The last line of defence: even if application code has a bug, the database
    -- refuses to let stock go negative.
    quantity   INTEGER NOT NULL CHECK (quantity >= 0)
);

CREATE TABLE orders (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PLACED', 'CANCELLED')),
    total_cents     BIGINT       NOT NULL CHECK (total_cents >= 0),
    -- Idempotency: the client's key for "this one purchase attempt", and a hash of what it asked for.
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Two requests with the same key from the same user can never both create an order.
    -- This constraint, not application code, is what makes the guarantee hold under concurrency.
    CONSTRAINT uq_orders_user_key UNIQUE (user_id, idempotency_key)
);

-- "My orders, newest first". Without an index this would scan the whole table for every user.
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC, id DESC);

CREATE TABLE order_items (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id         BIGINT  NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id       BIGINT  NOT NULL REFERENCES products (id),
    quantity         INTEGER NOT NULL CHECK (quantity > 0),
    -- Price at the time of purchase. Later price changes must not rewrite history.
    unit_price_cents BIGINT  NOT NULL CHECK (unit_price_cents >= 0)
);

-- PostgreSQL does NOT index foreign key columns automatically. Without this, loading
-- the items of an order (and deleting an order) would scan the whole table.
CREATE INDEX idx_order_items_order ON order_items (order_id);
