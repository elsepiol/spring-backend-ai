# Level 3 - Orders, stock and concurrency

Levels 0-2 made a correct app for ONE user at a time. This level asks: what happens when
many people act at the same instant? Almost every real backend bug that survives testing is
a concurrency bug, and "how do you stop overselling?" is one of the most common interview
questions there is.

## What you can now do

| Call | Who | What |
|---|---|---|
| `PUT /api/v1/products/{id}/stock` `{"quantity":n}` | admin | set the exact stock level |
| `GET /api/v1/products/{id}/stock` | anyone | read it (no record reads as 0) |
| `POST /api/v1/orders` + `Idempotency-Key` header | logged in | buy; 201 new, 200 replay, 409 no stock |
| `GET /api/v1/orders/{id}` | owner or admin | others get 404 |
| `GET /api/v1/orders` | logged in | my orders, newest first, paged |
| `POST /api/v1/orders/{id}/cancel` | owner or admin | restores stock, once |

## 1. The lost update (read `ConcurrencyTest`, last test)

Naive code: read stock (1) -> check `>= 1` -> write `stock = 0`. Two requests interleave:
both read 1, both pass the check, both write 0. Two items sold, one owned. The default
isolation level in PostgreSQL (READ COMMITTED) does not prevent this: each statement sees
committed data, but nothing makes "read then write" one step.

The test forces that timing with a barrier so you can watch it happen, and it PASSES by
asserting the bug. Real traffic hits this rarely, which is why it ships to production.

## 2. Three fixes and their trade-offs

1. **Atomic conditional UPDATE** (what we use): `update inventory set quantity = quantity - :q where product_id = :id and quantity >= :q`, then check how many rows changed. The check and the change are one statement; PostgreSQL locks the row for its duration. Fast, no lock held across round trips. Look at `InventoryRepository.tryDecrement`.
2. **Pessimistic lock** (`select ... for update`): lock the row, read, decide, write. Correct, but the lock is held across several statements, so under load requests queue behind each other.
3. **Optimistic lock** (`@Version` column): read, then write `where version = :v`; on 0 rows changed, retry. Great when conflicts are rare; wasteful on a hot product where most attempts fail and retry.

Rule of thumb: if the rule can be written as a condition in one SQL statement, do that.
The `CHECK (quantity >= 0)` constraint is the backstop if application code ever has a bug.

## 3. Deadlocks and lock ordering

Order 1 locks product A then B; order 2 locks B then A. Each holds one lock and waits for the
other: deadlock; PostgreSQL kills one with an error. Fix: always lock in the same global
order. `OrderService` merges items into a `TreeMap` so products are always touched in
ascending id. `ordersListingTheSameProductsInOppositeOrderDoNotDeadlock` proves it.

## 4. Idempotency keys (retries must be safe)

The network can lose the response after the server did the work. The client cannot tell, so it
retries, and without protection the customer is charged twice. The client sends an
`Idempotency-Key` (a UUID per purchase attempt) and re-sends the SAME key on retry.

- A `UNIQUE (user_id, idempotency_key)` constraint is the real guarantee. Application checks
  alone have the same race as the stock bug.
- We insert the order row FIRST. Of 20 simultaneous identical requests exactly one insert wins;
  the others hit the constraint, roll back, look up the winner's order and return it
  (`200` + `Idempotent-Replayed: true`).
- We store a SHA-256 fingerprint of the request. Same key with a different body is a client
  bug -> `422`, never silently return the wrong order.
- Failed attempts are rolled back and not stored, so a retry after "out of stock" can succeed
  later.
- Why `TransactionTemplate` and not `@Transactional`: after a constraint violation PostgreSQL
  marks the transaction as failed; we must catch the exception AFTER the rollback to look the
  winner up in a fresh transaction. A method annotation cannot do that.

## 5. Smaller things that matter

- **Price snapshot**: `order_items.unit_price_cents` copies the price at purchase time. Changing a product later must not rewrite history (`anOrderKeepsTheOldPrice...`).
- **404, not 403, for someone else's order**: 403 confirms the order exists. This class of bug is called IDOR (insecure direct object reference) and is on the OWASP list.
- **N+1 queries**: loading 10 orders and then each order's items lazily is 1 + 10 queries. `hibernate.default_batch_fetch_size=50` loads items for a whole page at once. `OrderQueryCountTest` counts statements so a regression fails the build.
- **Foreign keys are not indexed automatically in PostgreSQL** (unlike the referenced primary key). `idx_order_items_order` exists for that reason.
- **Overflow**: totals use `multiplyExact`/`addExact`; a bug becomes an exception instead of a negative total.

## 6. System design talk track (how this scales)

- **~1,000 requests/s**: this design is fine on one app server and one PostgreSQL. Each order is a short transaction touching a few rows.
- **A hot product (flash sale)**: all buyers contend for ONE row. Throughput for that product is bounded by how fast one row can be updated serially (thousands per second, not millions). Options: split the stock across N rows (buckets) and decrement a random one; or move to a queue that serialises purchases per product; or accept orders into a queue and confirm asynchronously.
- **100,000 users**: connection pool size (default 10) becomes the bottleneck first: a slow transaction holding a connection starves everyone. Keep transactions short, never call another service or send email inside one, and size the pool to what the database can handle, not to the number of threads.
- **Google scale vs a small enterprise**: a small company keeps one PostgreSQL and wins on simplicity. At very large scale stock is sharded by product, orders are written to a log (Kafka-style), reads go to replicas or caches, and "exactly once" is built from at-least-once delivery plus idempotency, i.e. exactly what Section 4 does by hand. (Levels 4-5 explore this.)

## 7. Interview questions you can now answer

1. How do you prevent overselling? (atomic conditional update; why not read-then-write; row locks; the constraint backstop)
2. What is a deadlock and how do you avoid one? (consistent lock ordering)
3. What is idempotency and how do you implement it for a payment/order endpoint?
4. READ COMMITTED vs REPEATABLE READ vs SERIALIZABLE, and which anomaly each prevents.
5. Optimistic vs pessimistic locking: when each?
6. What is the N+1 problem and how do you detect it?
7. Why return 404 instead of 403 for another user's resource?

## Where to look (the "feel")

- `V3__orders_and_inventory.sql` - constraints and indexes are part of the design, not decoration.
- `OrderService.createOrder` - read top to bottom; each comment explains one decision.
- `target/surefire-reports/` after `./mvnw verify` - one text file per test class.
- Docs: Hibernate batch fetching, PostgreSQL "Explicit Locking" and "Transaction Isolation" chapters, Spring `TransactionTemplate`.

## Exercise 3 (`Level3ExerciseTest`)

**Part A** - `GET /api/v1/admin/inventory/low-stock?threshold=5` returns
`[{"productId":..,"quantity":..}]` for stock `<= threshold`, lowest first. Only ADMIN may call it:
add a rule to `SecurityConfig` (the catch-all alone would let ordinary users in).

**Part B** - `POST /api/v1/products/{id}/stock/adjust` with `{"delta": -2}`: 204 on success, 409 if
the result would be negative (stock unchanged), 404 if there is no stock record, 400 if delta is 0.
The test fires 20 simultaneous `-1` calls at stock 10 and demands exactly 10 successes, so a
read-then-write solution will fail. Hint: copy the shape of `tryDecrement`.

Run `./mvnw test -Pexercises` (Docker required). Stuck? The reference lives in `solutions/level-3/`.
