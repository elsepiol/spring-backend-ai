-- Level 4. A version number for every product, bumped by Hibernate on each update (@Version).
-- It is the product's ETag ("has it changed since you last saw it?") and it stops two
-- concurrent updates from silently overwriting each other (optimistic locking).
-- Adding a column with a constant DEFAULT is instant in PostgreSQL 11+: no table rewrite.
ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Keyset pagination "cheapest first": WHERE (price_cents, id) > (?, ?) ORDER BY price_cents, id.
-- The index has the SAME columns in the SAME order as the ORDER BY, so PostgreSQL can jump
-- straight to the cursor position and read the next rows in order: no scan, no sort.
-- id is included so the order is total (two products can have the same price).
-- On a big production table you would use CREATE INDEX CONCURRENTLY so writes are not
-- blocked while it builds (see docs/learn/04-performance.md).
CREATE INDEX idx_products_price_id ON products (price_cents, id);
