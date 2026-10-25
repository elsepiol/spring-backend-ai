# Level 5: Hundreds of thousands of users

Goal: understand what changes when the app runs as many instances, for many users, and some work no longer fits inside a request. Three tools: move slow or fragile work **out of the request** (a queue, here the transactional outbox), protect the system from **too much traffic** (rate limiting), and spread **reads** over copies of the database (read replicas). Each one buys capacity with a new kind of inconsistency, and the interview questions are mostly about that price.

Read Levels 3 and 4 first: idempotency, transactions, caching and the connection pool all come back here.

Versions: Spring Boot 4.1.2, PostgreSQL 17 in CI. Measured numbers come from the machine that built this level (4 CPU cores, PostgreSQL 16, app and database on the same machine): comparisons, not a benchmark.

---

## 1. The picture

```
POST /api/v1/orders
  -> RateLimitFilter            too many from this caller?            -> 429 + Retry-After   (section 4)
  -> OrderService.place         one transaction, on the PRIMARY:
        insert order, take stock,
        insert outbox_events row  "OrderPlaced"                                               (section 2)
  <- 201 Created                (the customer is not kept waiting for anything else)

meanwhile, in the background, on every instance:
  OutboxWorker  claims due events (FOR UPDATE SKIP LOCKED), runs handlers,
                OrderPlacedNotifier -> notifications row                                       (section 3)

GET /api/v1/products            read-only: may be served by a REPLICA (slightly behind)       (section 5)
GET /api/v1/orders/{id}         your own data: always the PRIMARY
```

---

## 2. What is new in the repo

| Path | What it is |
|---|---|
| `db/migration/V5__outbox_and_notifications.sql` | `outbox_events` (with a partial index) and `notifications` |
| `outbox/Outbox.java` | Writes an event inside the current transaction |
| `outbox/OutboxWorker.java` | Claims, runs and records events: lease, backoff, dead letters |
| `outbox/OutboxPoller.java` | Runs the worker every second (`app.outbox.worker.enabled`) |
| `outbox/OutboxHandler.java`, `OutboxMessage.java` | The contract for "code that handles one event type" |
| `order/OrderEvent.java` | The JSON payload of `OrderPlaced` and `OrderCancelled` |
| `notification/*` | `GET /api/v1/notifications`, and `OrderPlacedNotifier`, the first handler |
| `ratelimit/*` | Token bucket, limiter, rules, and the filter SecurityConfig adds |
| `db/ReadReplicaConfig.java` | Two pools and the read/write router, only when `DB_REPLICA_URL` is set |
| `AuthService`, `OrderService`, `ProductService` | Some reads lost `readOnly = true`, on purpose (section 5.3) |
| `src/test/.../support/PostgresReplica.java` | A real streaming replica for tests, with replay you can pause |

---

## 3. Async work: the transactional outbox

### 3.1 The problem: "and then send the email"

After an order we want to send a confirmation email, tell the warehouse, update analytics. Doing it inside the request is slow (the customer waits for a mail server) and fragile (mail server down = purchase fails). Doing it inside the transaction is worse: a 2-second SMTP call holds a pool connection for 2 seconds (Level 4).

So the work goes to a **queue**: the request records "this needs doing", returns, and a worker does it later. The trap is how the request records it. "Commit the order, then publish a message to Kafka/RabbitMQ" is a **dual write**: two systems, no shared transaction.

```
commit order        publish message
    ok         ->      fails / process dies here      => order exists, email never sent
publish message     commit order
    ok         ->      fails (out of stock, crash)    => email sent for an order that does not exist
```

No ordering of two separate writes is safe. You need ONE write.

### 3.2 The fix: write the event into the same transaction

`OrderService.createOrder` ends with

```java
outbox.add(OrderEvent.PLACED, order.getId(), json.writeValueAsString(new OrderEvent(...)));
```

which is an `INSERT INTO outbox_events` on the same connection, in the same transaction, as the order. They commit together or roll back together. `OutboxWorkerTest.anOrderAndItsEventCommitTogetherOrNotAtAll` shows a successful order with its event, and an out-of-stock attempt with neither. `Outbox.add` is `Propagation.MANDATORY`: calling it outside a transaction fails loudly instead of quietly turning back into a dual write.

### 3.3 The worker

Every app instance runs an `OutboxPoller` that calls `OutboxWorker.runOnce()` every second. One run:

1. **Claim** a batch with a single statement:

   ```sql
   update outbox_events
   set available_at = now() + make_interval(secs => 30), attempts = attempts + 1
   where id in (select id from outbox_events
                where status = 'PENDING' and available_at <= now()
                order by available_at, id limit 20
                for update skip locked)
   returning id, type, aggregate_id, payload, attempts
   ```

   - `FOR UPDATE` locks the chosen rows; **`SKIP LOCKED`** makes a second worker, claiming at the same instant, skip rows the first one has locked instead of waiting for them. That is how a table becomes a work queue that many workers share. `fourWorkersRacingNeverDeliverAnEventTwice` runs 4 workers over 200 events and counts exactly one delivery each.
   - Pushing `available_at` 30 seconds into the future is a **lease**: the claim commits at once, so no lock is held while handlers work, and if this worker dies the events become due again when the lease runs out (`anEventClaimedByACrashedWorkerIsTakenOverWhenTheLeaseRunsOut`).
2. **Run** each event's handler, outside any transaction of the worker's.
3. **Record** the outcome: `DONE`; or on an exception, back to waiting with **exponential backoff** (2 s, 4 s, 8 s...) so a struggling mail server gets room instead of a retry storm; or after `max-attempts` (5), **`DEAD`**: a dead letter, kept for a human. An event type no handler knows is retried the same way, because during a rolling deploy an old instance may see a type that only the new version handles.

The idle cost is one indexed `UPDATE` per second per instance. `idx_outbox_due` is a **partial index** (`WHERE status = 'PENDING'`): millions of done events never enter it, so it stays tiny.

Measured: one instance with one worker thread drained 20,000 queued `OrderPlaced` events at about **840 events per second**, each one a handler transaction plus a status update.

### 3.4 At least once, so every handler is idempotent

Look at where the worker can die: after the handler ran, before `DONE` was written. Next time, the event is delivered again. A queue that never loses messages delivers some of them twice; that is **at-least-once delivery**, and "exactly once" in practice means at-least-once plus idempotent handlers (Level 3's idempotency keys, from the other side).

`OrderPlacedNotifier` is idempotent through the database: `notifications.event_id` is `UNIQUE` and the insert is `ON CONFLICT (event_id) DO NOTHING`. `deliveringTheSameEventTwiceStillNotifiesOnce` puts a finished event back to `PENDING` and checks there is still one notification. For a real email you would do the same with a "sent" table keyed by event id, checked before sending, because an email cannot be un-sent.

### 3.5 Eventual consistency

The 201 for an order comes back before its notification exists. `NotificationApiTest` waits for it with Awaitility. That gap, "true soon but not yet", is **eventual consistency**, and clients must be designed for it: show "processing...", poll, or push an update.

### 3.6 Is this a real queue?

Yes, for this size. PostgreSQL with `SKIP LOCKED` handles thousands of jobs per second, needs no new infrastructure, and gives you the transaction for free. Java's db-scheduler and Rails' Solid Queue are built on the same idea. You outgrow it when you need:

| Need | Typical answer |
|---|---|
| Hundreds of thousands of messages per second, replay of history, many independent consumers | Kafka (a log, consumers track their own position) |
| Routing, per-message acknowledgement, delays, priorities | RabbitMQ |
| Fully managed, pay per message | AWS SQS / SNS, Google Pub/Sub |

Even then the outbox stays: the relay that reads `outbox_events` publishes to Kafka instead of calling handlers in-process, or a change-data-capture tool (Debezium) reads the table straight from PostgreSQL's write-ahead log.

---

## 4. Rate limiting

### 4.1 Why

Level 2 promised it: nothing stopped 1,000 password guesses per second, and each costs a bcrypt hash, roughly 100 ms of CPU. Rate limiting caps how fast one caller can use an endpoint, so one client (an attacker, or just a buggy loop) cannot take capacity from everyone else.

### 4.2 The token bucket

`TokenBucket` holds up to `capacity` tokens and starts full. Each request takes one. Tokens drip back at `capacity / period` (10 per minute: one every 6 seconds), never above capacity.

```
10 requests at once: all allowed (a burst), bucket empty
the 11th, 2 s later: refused, Retry-After: 4   (next token due 6 s after the last one)
```

Compare the naive **fixed window** ("max 10 per calendar minute"): 10 at 12:00:59 and 10 more at 12:01:00, twice the limit in one second. A **sliding window** fixes that but needs a log of timestamps. Token buckets allow bursts, keep the long-run rate, need two numbers per key, and are what most gateways use. Leaky bucket is the queue-shaped cousin: requests drain at a fixed rate instead of being refused.

Details in the code worth noticing:
- **No timer.** The refill is computed from the elapsed time when the bucket is used. Time comes from a `LongSupplier`, so `RateLimiterTest` uses a fake clock and is exact and instant.
- **`synchronized tryTake`.** Two requests from one client at the same instant must not both get the last token: Level 3's check-then-act race, in memory. `fiftySimultaneousRequestsGetExactlyTheCapacity` proves it.
- **Bounded storage.** Buckets live in a Caffeine cache (100,000 keys, dropped after an hour idle). Keys come from clients; an unbounded map is a way to run you out of memory.

### 4.3 Where it runs, and what it counts

`SecurityConfig` adds `RateLimitFilter` right after the authorization filter:
- requests refused anyway (no token, wrong role) never reach it and cost nobody quota;
- for the ones that do, the user is already known, so a limit can be "per user" (Exercise 5).

Login and register are limited **per client address**, 10 per minute (`app.rate-limit.auth.*`), and wrong passwords count. Answer: **429 Too Many Requests** with `Retry-After`, as problem-details JSON.

The client address is subtle. Behind a load balancer, `getRemoteAddr()` is the load balancer, so everyone shares one bucket. The real client is in `X-Forwarded-For`, but any client can send that header, so trusting it blindly lets an attacker pick a fresh "address" per request. Configure `server.forward-headers-strategy` so Spring Boot only accepts it from your own proxy.

Limiting login per **account** as well stops slow guessing from many addresses, but it lets an attacker lock a victim out on purpose by failing their login repeatedly. Real systems combine per-address limits, per-account delays that grow, CAPTCHAs, and alerts.

### 4.4 One instance is not the system

These buckets are in each instance's memory. With 4 instances behind a round-robin load balancer, a client gets up to 4 times the limit. Fine for abuse protection, wrong for "your plan includes 1,000 API calls per hour". For a global limit the counters must be shared: Redis with an atomic script (Bucket4j has Redis backends), or the limit moves in front of the app, into the API gateway or load balancer (nginx `limit_req`, Envoy, AWS API Gateway, Cloudflare). Large companies do both: coarse limits at the edge, precise ones in the service.

---

## 5. Read replicas

### 5.1 What a replica is

PostgreSQL writes every change to its **write-ahead log** (WAL) before applying it. A **streaming replica** is a copy of the database that receives the primary's WAL as it is written and replays it. It can serve reads, never writes, and it is always slightly behind: usually milliseconds, seconds or more when the replica or network is busy. That delay is **replication lag**.

Replicas help read-heavy systems: the primary does all the writes, replicas share the reads, and you can add replicas as reads grow. Writes do not scale this way; that takes partitioning or sharding.

`PostgresReplica` (test support) builds one for real: `pg_basebackup -h primary -R -X stream` copies the primary's data directory over a replication connection and writes the settings that make the copy start as a standby. That is the same command you would use on servers.

### 5.2 Routing: readOnly goes to the replica

With `DB_REPLICA_URL` set, `ReadReplicaConfig` creates two Hikari pools and puts a router in front:

```java
LazyConnectionDataSourceProxy routing = new LazyConnectionDataSourceProxy(primaryDataSource);
routing.setReadOnlyDataSource(replicaDataSource);
```

`@Transactional(readOnly = true)` runs on the replica, everything else on the primary. The lazy proxy is what makes this work: Hibernate gets a placeholder connection when the transaction starts, and the real connection is fetched only at the first SQL statement. By then the transaction has been marked read-only, so the proxy knows which pool to ask. A plain routing data source would have to choose before it knew. `readOnlyTransactionsRunOnTheReplicaAndEverythingElseOnThePrimary` checks with `pg_is_in_recovery()`, which is true only on a standby.

Without `DB_REPLICA_URL` nothing changes: one pool, as in Level 4.

### 5.3 The price: reads that must not be stale

From now on `readOnly = true` also means "a slightly old answer is acceptable". `ReadReplicaTest` pauses the replica's replay (`pg_wal_replay_pause()`), so it is guaranteed to be behind, and checks every case. While building this level, putting `readOnly` back made each of these fail:

| Read | If served by a lagging replica | So it runs on |
|---|---|---|
| `AuthService.login` | someone who registered a second ago gets 401; worse, right after a password change the OLD password still works | primary |
| `OrderService.get`, `listMine` | "my order was placed, and opening it says 404" | primary |
| the idempotency lookup in `place` | a retry misses the original order, hits the UNIQUE constraint, and gets 409 instead of the replayed order | primary |
| `ProductService.getById` (a cache miss) | after an update evicted the product, the miss reloads the OLD row from the replica and caches it for 10 minutes | primary |
| product list, scroll, stock display, search, low-stock report | shows the state of a moment ago | replica |

The pattern is **read-your-writes**: after you change something, you must see your change. It applies to data a user has just written themselves (their account, their orders) and to anything a decision depends on. A catalog page a few hundred milliseconds old harms nobody. Other ways to get read-your-writes, when "always the primary" is too blunt:
- after a write, pin that user's reads to the primary for a few seconds (a cookie or session flag);
- remember the WAL position of your write (`pg_current_wal_lsn()`) and read from a replica only once it has replayed that far (`pg_last_wal_replay_lsn()`), which is what `PostgresReplica.resumeReplay()` waits for;
- route to the replica with the least lag, and drop replicas that fall too far behind (check `pg_stat_replication.replay_lag` on the primary).

Look again at the cache row: combining two copies (cache and replica) stacks their staleness, and the cache makes it last. That bug is easy to ship and hard to see; the test catches it in one line.

### 5.4 Connections, again

Every instance now has two pools (`shop-db` and `shop-db-replica` in `/actuator/metrics`). 20 instances × 2 pools × 10 is 400 connections on the database side. Pool sizing from Level 4 still applies, per database server, and PgBouncer becomes standard at this point.

---

## 6. Testing

| Test | Needs Docker | Proves |
|---|---|---|
| `RateLimiterTest` | no | burst, steady refill, Retry-After, cap after a long pause, per key, thread-safe |
| `OutboxWorkerTest` | yes | commit-together, notification, duplicate delivery, 4 racing workers, backoff, DEAD, unknown type, lease takeover |
| `NotificationApiTest` | yes | the real background poller, end to end, eventually; only your own notifications |
| `RateLimitApiTest` | yes | 429 + Retry-After after the limit; per address; register shares the budget |
| `ReadReplicaTest` | yes | routing; the replica can be behind; login, own orders, idempotency and cache fill are not |

Three test-infrastructure lessons worth knowing beyond this project:
- **Background work in tests is shared state.** Spring keeps test contexts cached and alive, so a poller started by one test class keeps running while later classes run, and steals their events. `PostgresIntegrationTest` switches the poller off for every test; tests run the worker by hand; the one class that tests the real poller uses `@DirtiesContext` so its context, and its scheduler, are closed afterwards.
- **`@SpringBootTest(properties = ...)` loses to `@TestPropertySource`.** The base class sets defaults with `@TestPropertySource`, so a subclass that needs other values must use `@TestPropertySource` too.
- **Make timing deterministic.** A fake clock for the token bucket, `pg_wal_replay_pause()` for lag, a 100 ms backoff in the outbox test. Tests that "usually" see a race are worse than none.

---

## 7. Run it yourself

```
docker compose up -d db
BOOTSTRAP_ADMIN_EMAIL=admin@example.com BOOTSTRAP_ADMIN_PASSWORD=local-admin-password ./mvnw spring-boot:run
```

Place an order (as in Level 3), then within a second or two:

```
curl -s localhost:8080/api/v1/notifications -H "Authorization: Bearer $TOKEN"
docker compose exec db psql -U shop -d shop -c "select id, type, status, attempts, last_error from outbox_events order by id desc limit 5;"
```

Trip the login limit (11 attempts within a minute from your machine):

```
for i in $(seq 1 11); do curl -s -o /dev/null -w "%{http_code} " -X POST localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" -d '{"email":"nobody@example.com","password":"wrong password"}'; done; echo
# 401 401 401 401 401 401 401 401 401 401 429
```

**Break it on purpose:**
1. Make `OrderPlacedNotifier.handle` throw. Place an order and watch `attempts`, `available_at` and `last_error` change in `outbox_events`, until `DEAD`.
2. Stop the app while events are pending, start it again. Nothing is lost: why?
3. In `OutboxWorker.CLAIM_SQL` remove `skip locked` and run `fourWorkersRacingNeverDeliverAnEventTwice`. Does it still pass? What does a second worker do now when it reaches a row the first one has locked, and what does that cost?
4. Replace the `ON CONFLICT DO NOTHING` insert with a plain insert and run `deliveringTheSameEventTwiceStillNotifiesOnce`. The customer still gets one message, so what fails, and what happens to the event?
5. Put `readOnly = true` back on `AuthService.login` and run `ReadReplicaTest`. Read the failure.
6. Set `app.rate-limit.auth.capacity=2` and log in three times in a row.

---

## 8. Exercise 5: limit orders per user, and notify cancellations

Needs Docker running.

**Part A: at most N orders per period, per user.** `POST /api/v1/orders` beyond `app.rate-limit.orders.capacity` per `app.rate-limit.orders.period` (both already in `application.properties`) answers 429 with `Retry-After`, like the login limit. Counted per **user**, not per address: two users behind one office address are independent. Reading orders is not limited.
- `RateLimitProperties`: add an `orders` limit
- `RateLimitConfig`: add a rule for `POST /api/v1/orders` whose key is the authenticated user. The filter runs after authorization, so `SecurityContextHolder.getContext().getAuthentication()` is the caller; for our JWTs its name is the user id
- a refused order must not reach `OrderService` at all (the test checks the stock)

**Part B: tell the customer when an order is cancelled.** `OrderService.cancel` already writes an `OrderCancelled` event. Nobody handles it yet, so today those events are retried and end up `DEAD` (check `outbox_events`). Write the handler: the customer gets the notification `Order #<id> was cancelled.`, exactly once even if the event is delivered twice.
- one new class in `notification`, the same shape as `OrderPlacedNotifier`

```
./mvnw test -Pexercises
```

Done when the Level 5 exercise tests pass and `./mvnw verify` still passes.

Surprise: two Part A tests pass before you write anything (`theLimitIsPerUserNotShared` and `readingOrdersIsNotLimited`). Why? They guard against limiting too much, which is the classic way a rate limit hurts real users.

**Read first** (search the page title if a link has moved):
- Transactional outbox pattern (microservices.io): https://microservices.io/patterns/data/transactional-outbox.html
- PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED` (the locking clause): https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
- PostgreSQL hot standby and streaming replication: https://www.postgresql.org/docs/current/hot-standby.html
- Spring `LazyConnectionDataSourceProxy`, see `setReadOnlyDataSource`: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/LazyConnectionDataSourceProxy.html
- RFC 6585, status 429: https://datatracker.ietf.org/doc/html/rfc6585#section-4
- Token bucket (Wikipedia): https://en.wikipedia.org/wiki/Token_bucket

**Answer in your own words:**
1. Why is the cancellation event written inside the cancel transaction, and not sent after it commits?
2. Your handler can run twice for one event. Name two different ways that can happen.
3. With 4 app instances, how many orders per minute can one user really place under your limit? What would make it exact?
4. Is "my notifications" (`NotificationService.latestFor`) read from the primary or a replica? Should it be?

**Stretch (no tests):**
- Add a `DELETE` job that removes `DONE` events older than 7 days, in batches of 1,000 so it never locks the table for long.
- Add `RateLimit-Remaining` to allowed responses, so a well-behaved client can slow down before it hits 429.
- Add `GET /api/v1/admin/outbox/dead` listing dead events, and `POST .../{id}/retry` putting one back to `PENDING`.

Reference solution: `solutions/level-5/` (`RateLimitProperties`, `RateLimitConfig`, `OrderCancelledNotifier`). Compare after you try.

---

## 9. The scaling lens (Level 5)

- **Hundreds of thousands of users** usually means thousands of requests per second at peak, mostly reads, with spikes. Levels 4 and 5 together answer it: caches and replicas for reads, short transactions and a bounded pool for writes, queues for anything slow, and rate limits so one client cannot take everyone's capacity.
- **The write path is the hard limit.** Every order still goes through one primary. When that is not enough: make each write cheaper (fewer indexes, batching), then **partition** data (orders by month), then **shard** (users 0-999,999 on cluster A...). Each step makes queries across the pieces harder.
- **Queues decouple, and hide failures.** A broken handler does not break checkout any more; it fills a table instead. Monitor queue depth and age (the oldest `PENDING` event) and dead letters, or you find out from customers.
- **Backpressure.** When workers fall behind, the queue grows instead of the request latency. That is the point, until it grows forever. Scale workers on queue depth, and shed load at the edge with rate limits and 503s (Level 4).
- **Consistency is now a product decision.** Which screens may be a second old, which must be exact? Engineers propose; product and business decide. In interviews, say which you chose and why.
- **At Google or Meta scale** the same ideas are separate systems run by separate teams: global rate limiting services, log-based messaging (Kafka-like) between every service, replicas in every region with read-your-writes handled by session tokens, and change-data-capture feeding search, caches and analytics from the database log.

---

## 10. Interview talk track

"Side effects of an order, like the confirmation, go through a transactional outbox: the event row is inserted in the same transaction as the order, so there is no dual write. Workers on every instance claim due events with `FOR UPDATE SKIP LOCKED` and a lease, retry failures with exponential backoff and park them as dead letters after five attempts. Delivery is at least once, so handlers are idempotent, here with a unique constraint on the event id. Login and registration are rate limited per client address with a token bucket that answers 429 with `Retry-After`. It is per instance, so for a global quota I would move the counters to Redis or the gateway. Read-only transactions can be routed to a streaming replica through Spring's `LazyConnectionDataSourceProxy`. Anything that needs read-your-writes stays on the primary: login, a customer's own orders, the idempotency check, and cache refills. I proved each of those against a real replica with replay paused, which is how I found that a cache miss from a lagging replica would re-cache stale data for the full TTL."

**Questions you should now be able to answer:**
1. What is a dual write, and why can't you fix it by ordering the two writes carefully?
2. How does the transactional outbox work? What does the relay do, and what can replace it (CDC)?
3. What does `FOR UPDATE SKIP LOCKED` do, and why is it the core of a database-backed queue?
4. At-least-once vs at-most-once vs "exactly once": what does each really mean?
5. Why exponential backoff, and what is a dead letter queue for?
6. Token bucket vs fixed window vs sliding window: trade-offs?
7. Why is an in-memory rate limiter wrong for a paid API quota, and what would you use instead?
8. How does PostgreSQL streaming replication work? What is replication lag, and how do you measure it?
9. What is read-your-writes consistency, and three ways to provide it with replicas?
10. Why can a cache plus a read replica serve stale data for the full TTL, and how do you prevent it?
11. What is eventual consistency, and how should a client UI deal with it?
