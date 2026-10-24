-- Flyway runs this once, in version order, and records it in flyway_schema_history.
-- Never edit a migration that has been applied anywhere: add V2__..., V3__... instead.
CREATE TABLE products (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku         VARCHAR(40)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    price_cents BIGINT       NOT NULL CHECK (price_cents >= 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_products_sku UNIQUE (sku)
);
