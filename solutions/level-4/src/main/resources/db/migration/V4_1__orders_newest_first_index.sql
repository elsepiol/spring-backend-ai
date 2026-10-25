-- SOLUTION (Exercise 4B). "My orders, newest first" is WHERE user_id = ? [AND id < ?] ORDER BY id DESC.
-- The index's columns, in order: the equality column first, then the ORDER BY column in the
-- same direction. PostgreSQL then reads exactly the rows it returns, already in order.
CREATE INDEX idx_orders_user_id ON orders (user_id, id DESC);

-- The Level 3 index was ordered by created_at, but no query sorts by created_at, so every
-- listing still had to sort. It only cost time on every INSERT. Gone.
DROP INDEX idx_orders_user_created;
