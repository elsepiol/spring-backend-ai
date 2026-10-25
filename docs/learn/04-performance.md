# Level 4: Thousands of requests per second

Goal: know where the time goes in a request, and use the four standard tools that make an API fast under load: **cache** what is read often, **don't send** what the client already has (ETags), make every query **cheap at any depth** (indexes and keyset pagination), and **bound the waiting** (the connection pool). You will measure each one instead of trusting it, because "it should be faster" is exactly what interviewers push back on.

Read Levels 1 to 3 first. Same method: read a section, run the command, break something on purpose, then do the exercise.

Versions: Spring Boot 4.1.2, Caffeine 3.2.4, HikariCP 7.0.2, PostgreSQL 17 in CI. The numbers below were measured on the machine that built this level (4 CPU cores, PostgreSQL 16, with the app, the database and the load generator all on that one machine). Treat them as comparisons between approaches, not as a benchmark; yours will differ.

---

## 1. The picture

```
client (browser, app, CDN)
   |  1. "I have version 3"  If-None-Match: "3"   ->  304, no body         (section 5)
   v
Tomcat thread (200 of them)
   v
ProductService.getById
   |  2. in the cache?  ->  answer from memory, no database at all         (section 4)
   v
transaction -> HikariCP pool (10 connections)
   |  3. no free connection within 2 s?  ->  503 + Retry-After               (section 8)
   v
PostgreSQL
      4. query reads only the rows it returns: an index in the right order  (sections 6, 7)
```

Each step either removes work or caps how long anyone waits. Level 1 said the first limit is the connection pool, not the CPU. This level is about spending fewer and shorter turns on that pool.

---

## 2. What is new in the repo

| Path | What it is |
|---|---|
| `db/migration/V4__product_version_and_price_index.sql` | `version` column on products, and the index for keyset pagination |
| `cache/CacheConfig.java` | Switches on `@Cacheable`, and orders the cache proxy outside the transaction |
| `product/ProductService.java` | `@Cacheable` read, `@CacheEvict` on update, `scrollByPrice` |
| `product/ProductController.java` | ETag + `Cache-Control` on GET, `PUT` with optional `If-Match`, `GET /scroll` |
| `product/Product.java` | `@Version` field and `update(...)` |
| `product/Cursor.java`, `CursorPage.java` | Opaque keyset cursors and the page shape that carries them |
| `product/ProductRepository.java` | The keyset query, as plain SQL in a constant (so a test can EXPLAIN it) |
| `error/ApiExceptionHandler.java` | 400 bad cursor, 412 stale `If-Match`, 409 lost race, 503 pool exhausted |
| `application.properties` | `spring.cache.*`, `spring.datasource.hikari.*`, `/actuator/metrics` |
| `security/SecurityConfig.java` | `/actuator/**` (apart from health and info) is ADMIN only |
| `scripts/LoadTest.java` | A tiny load generator that needs nothing but a JDK |
| `src/test/.../support/QueryPlan.java` | Runs EXPLAIN and lets tests assert on the plan |
| `pom.xml` | Gained `spring-boot-starter-cache` and `caffeine` |

New endpoint: `PUT /api/v1/products/{id}` (ADMIN) with `{"name":"...","description":"...","priceCents":...}`. The SKU cannot change. Without an update there would be nothing to evict, and nothing to protect with `If-Match`.

---

## 3. Measure first

Two instruments, both in the repo.

**`scripts/LoadTest.java`** keeps N clients busy for a number of seconds and reports requests per second, latency percentiles and status codes. Java 21 runs a single source file directly, so there is nothing to install:

```
java scripts/LoadTest.java http://localhost:8080/api/v1/products/1 50 15
```

**`/actuator/metrics`** (ADMIN token needed) shows what the app itself sees: `cache.gets` with `result:hit` or `result:miss`, and `hikaricp.connections.active` and `.pending` (requests waiting for a connection).

What this level's endpoints did with 50 concurrent clients, against 1,000,000 products:

| Request | Requests/s | p50 latency | Why |
|---|---:|---:|---|
| `GET /products/{id}`, cache off (`SPRING_CACHE_TYPE=none`) | ~4,500 | 8 ms | one indexed query per request |
| `GET /products/{id}`, cache on | ~8,000 | 5 ms | no database at all |
| same, with `If-None-Match` (304) | ~9,700 | 4 ms | no database and no body |
| `GET /products/scroll?cursor=...` (half way through 1M rows) | ~3,600 | 11 ms | keyset: reads 21 index entries |
| `GET /products?page=25000&size=20` (same depth, offset) | 26 | 1,950 ms | reads 500,020 rows, plus `count(*)` |
| `GET /products?page=0&size=20` | 47 | 1,056 ms | `count(*)` over 1M rows, on every request |

Read the last two rows twice. Page 0 is slow too, because the offset listing returns `totalElements` and that needs a full `count(*)`. Those requests held their connections for so long that the 10-connection pool ran dry, and some requests got **503** after waiting the configured 2 seconds (section 8). That is what overload looks like in production: not a crash, but a queue in front of the pool.

On the cache rows: with the database on the same machine, one indexed query is cheap, so the cache "only" gives about 1.8x. With a real network hop to a database shared by many app instances, the gain is larger. More important is what does NOT show in requests per second: with the cache on, the database does no work for those reads, and that capacity goes to everything else.

---

## 4. Caching with Caffeine

### 4.1 Cache-aside

```java
@Cacheable(cacheNames = "products", key = "#id", sync = true)
@Transactional(readOnly = true)
public ProductResponse getById(long id)
```

Spring wraps `ProductService` in a proxy. On a call it looks for `id` in the `products` cache; on a hit it returns the stored value and the method never runs. On a miss it runs the method and stores the result. This pattern, where the application checks the cache and fills it on a miss, is called **cache-aside**.

Configuration in `application.properties`:

```
spring.cache.type=caffeine
spring.cache.cache-names=products
spring.cache.caffeine.spec=maximumSize=10000,expireAfterWrite=10m,recordStats
```

- **Caffeine** is an in-memory cache inside our JVM. A lookup is a hash map access: nanoseconds, no network.
- **`maximumSize`**: a cache without a bound is a memory leak with extra steps. When full, Caffeine evicts the entries least likely to be used again (its policy is called W-TinyLFU).
- **`expireAfterWrite=10m`**: every entry is thrown away 10 minutes after it was loaded, whatever happens. That is the safety net in 4.4.
- **`recordStats`**: feeds the hit/miss numbers in `/actuator/metrics`.

### 4.2 What goes in the cache

We cache `ProductResponse`, an immutable record, never the `Product` entity. An entity is mutable and belongs to the database session that loaded it: two requests sharing one entity object would see each other's changes, and lazy fields would fail once the session is gone. Cache values that are immutable and complete.

### 4.3 The cache must sit outside the transaction

`@Cacheable` and `@Transactional` are both proxies around the same method. Which one is outside decides what a cache hit costs:

```
cache outside (what we configure)          transaction outside (the trap)
  cache hit -> return                        open transaction -> borrow connection
  (no connection touched)                      cache hit -> return
                                             commit -> give connection back
```

In the trap, a cache hit runs **zero SQL** but still opens a database session and borrows a pool connection. It looks cached in every log and still competes for the scarcest resource. `CacheConfig` sets `@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 1)`: a smaller order is the outer proxy, and transactions use `LOWEST_PRECEDENCE`.

With both at the default order the nesting depends on which proxy Spring registers first. In this app it happens to come out right, which is an accident you should not rely on. When the transaction was forced outside while building this level, `ProductCacheTest` reported exactly this: `SQL statements: 0`, `database sessions: 1`.

The same ordering is why eviction is safe. `@CacheEvict` on `update` runs after the method returns, and with the cache outside, that is **after the commit**. Evicting before the commit would let a concurrent reader load the old row and put it straight back.

### 4.4 Staleness: the price you pay

A cache is a copy, and copies go stale. `ProductCacheTest.aChangeMadeBehindTheAppsBackIsNotSeenUntilTheEntryIsEvicted` changes a price with plain SQL and shows the API still returning the old one. That test passes by showing the stale read. The ways this happens in real life:

- **Writes that bypass the service**: a manual SQL fix, a batch job, another application.
- **Several instances.** Run three copies of this app behind a load balancer and each has its own Caffeine cache. An update served by instance A evicts A's copy only; B and C keep the old price until their entries expire. This is the big one.
- **A race that remains.** A reader misses, loads the row, and is slow to store it; meanwhile a writer commits and evicts; then the reader stores the old row. Rare, but possible.

`expireAfterWrite=10m` bounds all three: no copy lives longer than 10 minutes. So the real design question is "how stale may this data be?" A product description: minutes are fine. A price: depends on the business (many shops re-check the price at checkout, which `OrderService` does, since it reads products from the repository, not the cache). Stock: not at all, which is why stock is not cached. It changes on every order, through SQL the cache never sees.

When local caches are not enough: a **shared cache** (Redis, Memcached) gives every instance the same copy, at the cost of a network hop per lookup. Or keep local caches and **broadcast evictions** (Redis pub/sub, Kafka) to every instance. Spring's cache abstraction lets you switch the provider with `spring.cache.type` without touching `@Cacheable` code.

### 4.5 Stampedes, and what is not cached

- **`sync = true`.** When a popular product is evicted, hundreds of requests can miss at the same instant and all run the same query. That is a cache stampede, also called a thundering herd. With `sync = true` one caller loads the value and the others wait for it. `twentySimultaneousMissesForOneProductRunOneQuery` fires 20 requests at once and counts exactly 1 SQL statement. Without `sync` it counted 4 when this was written, and under real load it would be far more.
- **Not-found is not cached.** An exception is never cached, so a product created a second later is found. The trade-off: an attacker requesting random ids misses every time and every miss reaches the database ("cache penetration"). Defences are rate limiting (Level 5) or caching "not found" for a short time.
- **Lists are not cached.** A page of products changes whenever any product on it changes, and every page size and cursor would be its own entry. Cache single things that are read far more often than written.

---

## 5. ETags and conditional requests

The cheapest request is one that sends nothing back.

```
$ curl -i localhost:8080/api/v1/products/1
HTTP/1.1 200
Cache-Control: no-cache
ETag: "0"
{"id":1,"sku":"MUG-001","name":"Blue Mug","description":null,"priceCents":49900,...}

$ curl -i localhost:8080/api/v1/products/1 -H 'If-None-Match: "0"'
HTTP/1.1 304
Cache-Control: no-cache
ETag: "0"
```

- **ETag** = "entity tag", an identifier of this version of the resource. Ours is the product's `version` column (section 5.2), in quotes, which is the syntax of a strong ETag.
- **`If-None-Match`**: "send it only if it is no longer this version". If it still matches, the answer is **304 Not Modified** with no body. Spring does this for you: when a controller returns a `ResponseEntity` with an ETag for a GET, Spring compares it with `If-None-Match` before writing the body.
- **`Cache-Control: no-cache`** means "you may store this, but check with me before using it". Despite the name, it does not mean "do not cache"; that is `no-store`. Spring Security sends `no-cache, no-store, max-age=0, must-revalidate` on every response by default. With `no-store` a browser never keeps a copy, so it never has an ETag to send back. Setting our own `Cache-Control` replaces that default for this response only; errors such as a 404 still get Security's safe one.
- The alternative is **`max-age=60`**: the client and any CDN may reuse the copy for 60 seconds without asking at all. That means zero requests instead of cheap ones, but the data can be up to 60 s stale. Same trade-off as section 4.4, now outside your servers.

Why our ETag is cheap: thanks to the cache, a 304 costs no database work at all. Compare Spring's `ShallowEtagHeaderFilter`, which builds the whole response and then hashes it to make an ETag. It saves bandwidth but none of the server's work.

### 5.1 If-Match: no more lost updates between people

Two admins open the same product, each sees ETag `"0"`. Alice saves first; Bob, working from his old copy, saves second and silently overwrites Alice's change. The fix is the write-side twin of `If-None-Match`:

```
$ curl -i -X PUT .../products/1 -H 'If-Match: "0"' ... -d '{"name":"Blue Mug","priceCents":45000}'
HTTP/1.1 200
ETag: "1"

$ curl -i -X PUT .../products/1 -H 'If-Match: "0"' ... -d '{"name":"Red Mug","priceCents":1}'
HTTP/1.1 412
{"detail":"Product 1 was changed by someone else. Fetch it again and retry","status":412,"title":"Precondition Failed"}
```

**412 Precondition Failed**: "you asked me to do this only if it is still version 0, and it is not". The client re-reads, shows the person what changed, and lets them decide. `If-Match` is optional here; a `*` or a missing header means "no check". A stricter API would require it and answer **428 Precondition Required** without one.

### 5.2 @Version: no more lost updates between transactions

`If-Match` compares versions in our code, but two PUTs carrying the same correct ETag can still race through that check at the same instant. `@Version` on `Product` closes the gap in the database. Hibernate writes every update as

```sql
update products set name = ?, ..., version = 4 where id = ? and version = 3
```

If another transaction got there first, 0 rows match, Hibernate throws, nothing is written, and we answer **409**. This is the optimistic locking that Level 3 mentioned: no lock is held while the user thinks, conflicts are detected when saving. `simultaneousUpdatesNeverSilentlyOverwriteEachOther` fires 10 PUTs at once and checks that the final version equals the number of 200s.

---

## 6. Pagination: offset vs keyset

Level 1 promised this. Offset pagination is `order by id limit 20 offset 500000`: PostgreSQL produces 500,020 rows and drops 500,000 of them.

| Query on 1M products (`EXPLAIN ANALYZE`) | Time | Rows read |
|---|---:|---:|
| `order by id limit 20 offset 0` | 0.09 ms | 20 |
| `order by id limit 20 offset 500000` | 133 ms | 500,020 |
| `order by price_cents, id limit 20 offset 500000` | 362 ms | 500,020 |
| keyset: `where (price_cents, id) > (500000, 0) order by price_cents, id limit 21` | **0.19 ms** | 21 |
| the same keyset condition written with `OR` (6.2) | 288 ms | 500,021 |
| `select count(*) from products` | 63 ms | 1,000,000 |

Offset cost grows with the page number. Keyset cost does not: page 50,000 costs the same as page 1.

### 6.1 How keyset works

Instead of "skip N rows", the client says "the rows after the last one I saw":

```
GET /api/v1/products/scroll?size=20
{"items":[...20 products...],"nextCursor":"MTIwMDo0Mg"}

GET /api/v1/products/scroll?size=20&cursor=MTIwMDo0Mg
{"items":[...the next 20...],"nextCursor":"..."}       nextCursor null = the end
```

The query (`ProductRepository.SCROLL_BY_PRICE_SQL`):

```sql
select * from products
where (price_cents, id) > (:price, :id)
order by price_cents, id
limit :limit
```

Details that matter:
- **A total order.** Many products share a price, so `id` breaks ties. Without it, rows with the same price on a page boundary can be skipped or repeated.
- **One extra row.** We fetch `size + 1`. If the extra row comes back there is a next page, so no `count(*)` is needed. The cost is that the API offers no "page 7 of 312". Infinite scroll, feeds and "load more" buttons never needed it.
- **Stable under inserts.** With offset, a cheaper product added while someone pages makes everything shift by one, and the next page repeats a product. A cursor says "after THIS row", so nothing shifts. `OrderApiTest.offsetPagesShiftWhenANewOrderArrivesWhilePaging` shows the offset bug; `ProductApiTest.aProductAddedWhileScrollingDoesNotShiftTheNextPage` shows it gone.
- **Opaque cursor.** `MTIwMDo0Mg` is just base64 of `1200:42`. It is opaque so that clients cannot build or depend on it, and we can change what is inside later (another sort, a version prefix). It is not a secret: editing one only gets you a different page, and anything malformed is a 400.
- **The first page** uses the position "before everything", `(Long.MIN_VALUE, Long.MIN_VALUE)`, so one query serves every page.
- **Trade-offs:** no jumping to page N, and every sort order you offer needs its own index (section 7). That is why big APIs (GitHub, Stripe, Slack) offer cursors for large collections.

### 6.2 The OR trap

`(a, b) > (x, y)` means `a > x or (a = x and b > y)`. Same rows. But look at the plans:

```
row comparison:  Index Scan using idx_products_price_id
                   Index Cond: (ROW(price_cents, id) > ROW(500000, 0))     <- jumps straight there
OR form:         Index Scan using idx_products_price_id
                   Filter: ((price_cents > 500000) OR ((price_cents = 500000) AND (id > 0)))
                   Rows Removed by Filter: 500000                           <- reads from the start
```

With the row comparison PostgreSQL can **seek** to the cursor position in the index. With the OR it can only walk the index from the beginning and filter, so keyset quietly turns back into offset. The giveaway in a plan is the word `Filter` where you expected `Index Cond`. Spring Data's built-in keyset scrolling (`ScrollPosition.keyset()`) generates the OR form, so check its plan before relying on it at depth.

---

## 7. Indexes, and how to read EXPLAIN

`EXPLAIN` shows the plan PostgreSQL chose; `EXPLAIN ANALYZE` also runs the query and adds what really happened. Read the plan from the innermost line outwards:

| You see | It means |
|---|---|
| `Seq Scan` | read the whole table. Fine for small tables, the classic problem for big ones |
| `Index Scan using X` + `Index Cond` | used index X to find exactly the matching rows |
| `Filter` + `Rows Removed by Filter: N` | read N rows that it then threw away: wasted work |
| `Sort` / `top-N heapsort` | rows were not already in order, so it sorted them |
| `actual ... rows=N` | how many rows that step really produced |
| `Bitmap Heap Scan` | collected matching row locations from an index first, then read them in disk order |

Without `idx_products_price_id`, the keyset query above becomes a parallel `Seq Scan` of all 1M rows plus a `top-N heapsort`: 76 ms instead of 0.19 ms.

Rules that cover most cases:
1. **Equality columns first, then the ORDER BY or range columns, in the same order (and direction).** `(price_cents, id)` serves `order by price_cents, id`; `(id, price_cents)` would not.
2. **Small tables ignore indexes, correctly.** On 10 rows, reading the one table page beats using an index. That is why `ProductQueryPlanTest` first inserts 20,000 rows and runs `ANALYZE` (which refreshes the statistics the planner estimates with). Test plans on realistic volume, inside a transaction you roll back.
3. **Indexes are not free.** Every INSERT and every update of an indexed column updates every index on the table, and each takes disk and memory. An index that no query uses is pure cost.
4. **Foreign keys are not indexed automatically** (Level 3). Neither is anything else you filter on.
5. **On a big live table**, `CREATE INDEX` blocks writes while it builds. Production uses `CREATE INDEX CONCURRENTLY`, which cannot run inside a transaction, so a Flyway migration that uses it must turn off Flyway's transaction for that script (see Flyway's `executeInTransaction` script configuration).

**Search with `%mug%`.** Level 1's search (`upper(name) like '%MUG%'`) cannot use a normal B-tree index, because the pattern does not start with a fixed prefix. PostgreSQL's `pg_trgm` extension indexes three-letter fragments ("trigrams"), which works for any substring. Measured on 1M products:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_products_name_trgm ON products USING gin (upper(name) gin_trgm_ops);
```

| `where upper(name) like '%DUCT 4242%' order by id limit 20` | Time |
|---|---:|
| no index (filters 1M rows) | 339 ms |
| trigram GIN index (`Bitmap Index Scan on idx_products_name_trgm`) | 19 ms |

When you also need relevance ranking, typo tolerance, synonyms or facets, that is when teams add a search engine (OpenSearch/Elasticsearch, Meilisearch). PostgreSQL full-text search (`tsvector`) sits in between.

### 7.1 An index that does not match its query

While building this level, `EXPLAIN` turned up a real mistake from Level 3. "My orders, newest first" runs

```sql
select * from orders where user_id = ? order by id desc limit 20
```

and `V3` created `idx_orders_user_created ON orders (user_id, created_at DESC, id DESC)`. The plan:

```
Limit
  ->  Sort  (Sort Key: id DESC, top-N heapsort)
        ->  Index Scan using idx_orders_user_created
              Index Cond: (user_id = 501)                      rows=100
```

The index finds the user's rows, but they come out ordered by `created_at`, and the query orders by `id`. So PostgreSQL reads **all** of that user's orders and sorts them, to return 20. With 100 orders that is invisible. For a business customer with 200,000 orders it is every request. The index was designed for a query nobody wrote. You fix it in Exercise 4.

---

## 8. The connection pool

Little's law (Level 1): throughput ≈ connections ÷ time each request holds one. Two ways to go faster: hold connections for less time (sections 4 to 7), or have more. More works only up to a point.

```
spring.datasource.hikari.pool-name=shop-db
spring.datasource.hikari.maximum-pool-size=${DB_POOL_SIZE:10}
spring.datasource.hikari.connection-timeout=${DB_POOL_TIMEOUT_MS:2000}
```

- **Small and fixed.** PostgreSQL runs each connection as a separate process. A few dozen busy connections keep its CPU cores and disk busy; hundreds mostly fight over locks and memory, so throughput goes down. HikariCP's "About Pool Sizing" starts from `(database CPU cores × 2) + disks` and says to measure. `minimum-idle` is left at its default, which equals the maximum: a fixed-size pool, ready when a spike arrives.
- **Multiply by instances.** Every app instance has its own pool. 20 instances × 10 = 200 connections on one database, twice PostgreSQL's default `max_connections` of 100. At that point teams put **PgBouncer** in front: many client connections share a few real database connections.
- **Fail fast.** By default a request waits 30 seconds for a connection. Under overload that means every Tomcat thread ends up waiting, and the app looks dead. With a 2 s timeout, the request fails, and `ApiExceptionHandler` answers **503 Service Unavailable** with `Retry-After: 1` instead of a vague 500. Clients and load balancers understand that as "overloaded, back off". You saw it in the load test in section 3.
- **Cached reads survive.** `ConnectionPoolTest` takes both connections of a 2-connection pool and holds them. The product list fails within about 300 ms with 503, and the cached product is still served with 200. A cache does not only make things faster; it keeps part of the site up when the database is struggling.
- **Keep transactions short** (Level 3): never call another service or send email inside one. A transaction that waits 2 seconds on a slow HTTP call holds a connection for 2 seconds.
- Watch `hikaricp.connections.pending` in `/actuator/metrics`. If it is often above zero, requests are queueing for connections.

What about Tomcat's 200 threads? They are not the limit while requests mostly wait on the pool. Java 21 virtual threads (`spring.threads.virtual.enabled=true`) make threads nearly free, which helps when requests wait on slow I/O such as other services. They do not create database connections, so the pool still decides database throughput.

---

## 9. Testing

| Test | Needs Docker | Proves |
|---|---|---|
| `CursorTest` | no | cursors round-trip; anything malformed is rejected |
| `ProductServiceTest` (new cases) | no | stale version refused without writing; `size + 1` and the next cursor |
| `ProductControllerTest` (new cases) | no | ETag and `Cache-Control`, 304, `If-Match` passed through or 412, size limits |
| `SecurityRulesTest.updatingIsAlsoAdminOnly` | no | PUT is ADMIN only |
| `ProductCacheTest` | yes | hits run no SQL and open no session; eviction; staleness; one query for 20 misses; bounded; metrics |
| `ProductApiTest` (new cases) | yes | ETags end to end, 412, concurrent PUTs, scrolling, no shift on insert, bad cursor |
| `ProductQueryPlanTest` | yes | EXPLAIN: the scroll query seeks in the index; the OR form and OFFSET waste rows |
| `ConnectionPoolTest` | yes | exhausted pool gives a fast 503; cached reads still work; pool size in metrics |
| `OrderApiTest.offsetPagesShiftWhenANewOrderArrivesWhilePaging` | yes | the offset bug, shown |

How "served from cache" is measured: Hibernate statistics (`spring.jpa.properties.hibernate.generate_statistics=true`, as in Level 3's N+1 test) count prepared statements and opened sessions per request. Several tests deliberately pass by showing a weakness: the stale cache read, the OR plan, the OFFSET rows, the offset page shift. They document the cost of each alternative, and they would fail if the behaviour changed.

---

## 10. Run it yourself

```
docker compose up -d db
BOOTSTRAP_ADMIN_EMAIL=admin@example.com BOOTSTRAP_ADMIN_PASSWORD=local-admin-password ./mvnw spring-boot:run
```

In a second terminal (bash; log in as in Level 2):

```
ADMIN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"local-admin-password"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
curl -s -X POST localhost:8080/api/v1/products -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" -d '{"sku":"MUG-001","name":"Blue Mug","priceCents":49900}'

curl -i localhost:8080/api/v1/products/1                          # ETag: "0", Cache-Control: no-cache
curl -i localhost:8080/api/v1/products/1 -H 'If-None-Match: "0"' # 304, no body
curl -i -X PUT localhost:8080/api/v1/products/1 -H "Authorization: Bearer $ADMIN" -H 'If-Match: "0"' \
  -H "Content-Type: application/json" -d '{"name":"Blue Mug","priceCents":45000}'   # 200, ETag: "1"
# the same PUT again -> 412, its If-Match is now out of date
curl -s "localhost:8080/api/v1/products/scroll?size=2"           # copy nextCursor into &cursor=...
curl -s -H "Authorization: Bearer $ADMIN" "localhost:8080/actuator/metrics/cache.gets?tag=result:hit"
```

Make a million products (in psql: `docker compose exec db psql -U shop -d shop`) and compare:

```sql
insert into products (sku, name, price_cents)
select 'B-' || g, 'Bench product ' || g, (g::bigint * 7919) % 1000000 from generate_series(1, 1000000) g;
analyze products;
explain analyze select * from products order by id limit 20 offset 500000;
explain analyze select * from products where (price_cents, id) > (500000, 0) order by price_cents, id limit 21;
```

Load test (the first is cached; compare with the stock endpoint, which queries every time):

```
java scripts/LoadTest.java http://localhost:8080/api/v1/products/1 50 15
java scripts/LoadTest.java http://localhost:8080/api/v1/products/1/stock 50 15
java scripts/LoadTest.java "http://localhost:8080/api/v1/products?page=0&size=20" 50 15
```

**Break it on purpose:**
1. Start the app with `SPRING_CACHE_TYPE=none` and repeat the first load test. Then watch `hikaricp.connections.active` while it runs.
2. In `CacheConfig`, add `@EnableTransactionManagement(order = 0, proxyTargetClass = true)` (from `org.springframework.transaction.annotation`) so the transaction becomes the outer proxy. Run `ProductCacheTest`. Which assertion fails, and what does that tell you about "zero SQL"?
3. Remove `sync = true` and run `twentySimultaneousMissesForOneProductRunOneQuery`. How many queries now?
4. Drop `idx_products_price_id` in psql and EXPLAIN the scroll query again.
5. Change a price with SQL (`update products set price_cents = 1 where id = 1`) and GET the product. How long until the API shows it, and what would make it sooner?
6. Set `spring.datasource.hikari.maximum-pool-size=2` and run the page-0 load test. Count the 503s.

---

## 11. Exercise 4: scroll my orders, and fix their index

Needs Docker running. Two parts, using everything above.

**Part A: `GET /api/v1/orders/scroll?size=10&cursor=...`.** The caller's own orders, newest first, with keyset pagination. Same response shape as products: `{"items":[...orders...],"nextCursor":"..."}`, `nextCursor` null on the last page. `size` from 1 to 100, default 20.
- copy the shape of `ProductService.scrollByPrice`: fetch `size + 1`, the cursor is the id of the last order shown (`Cursor.encode(id)` / `Cursor.decode(cursor, 1)`), and the first page starts "before everything" (for newest first, that is `Long.MAX_VALUE`)
- the query: this user's orders with `id` less than the cursor, newest first, at most `size + 1`. Spring Data can derive it from a method name if you add a `Limit` parameter (`org.springframework.data.domain.Limit`)
- files: `OrderController`, `OrderService`, `CustomerOrderRepository`

**Part B: make the index match the query.** Add a migration `src/main/resources/db/migration/V4_1__orders_newest_first_index.sql` (version 4.1 sits between V4 and future levels) that creates the index "my orders, newest first" needs (section 7.1 and rule 1 in section 7), and drops `idx_orders_user_created`, which nothing uses any more.

```
./mvnw test -Pexercises
```

Done when the Level 4 exercise tests pass and `./mvnw verify` still passes.

Surprise before you start: the Part A tests fail with **400**, not 404. Work out why. Hint: which mapping in `OrderController` does `/api/v1/orders/scroll` match today, and what is `"scroll"` converted to?

The Part B test inserts 50,000 orders in a rolled-back transaction and EXPLAINs both query shapes (first page and later page). It fails while the plan contains a `Sort`, and the failure message prints the plan, so read it.

If your local database already ran migrations from a later level, Flyway will refuse a new V4_1 ("detected resolved migration not applied"). Recreate the local database with `docker compose down -v`. Tests always start from an empty database, so they are not affected.

**Read first** (search the page title if a link has moved):
- Spring Data JPA, limiting query results and scrolling: https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html
- PostgreSQL, using EXPLAIN: https://www.postgresql.org/docs/current/using-explain.html
- PostgreSQL, indexes and ORDER BY: https://www.postgresql.org/docs/current/indexes-ordering.html
- "No offset" (Markus Winand, Use The Index, Luke): https://use-the-index-luke.com/no-offset
- Spring cache abstraction: https://docs.spring.io/spring-framework/reference/integration/cache.html
- HTTP conditional requests (MDN): https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Conditional_requests
- HikariCP, About Pool Sizing: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing

**Answer in your own words:**
1. What SQL does your derived query produce (turn on `spring.jpa.show-sql`)? Run it with `EXPLAIN ANALYZE` in psql before and after your migration. How many rows does each plan read?
2. Would an index on `(user_id, id)` (ascending) work too? Try it and look for `Index Scan Backward`.
3. `listMine` (offset) still returns `totalElements`. What does that cost for a customer with 200,000 orders, and what would you do about it?
4. Why is there no cache in front of the orders endpoints?

**Stretch (no tests):**
- Add the trigram index from section 7 for your Exercise 1 search, and prove with EXPLAIN that it is used. Then check which SQL Spring Data generates for `ContainingIgnoreCase`, and whether your index expression matches it exactly.
- Add `?sort=newest` to `/products/scroll`. What index does it need, and what goes into the cursor?
- Return `Cache-Control: max-age=30` for product reads instead of `no-cache`, and argue when that is the better choice.

Reference solution: `solutions/level-4/` (the three order files and the migration). Compare after you try.

---

## 12. The scaling lens (Level 4)

- **Thousands of requests per second is mostly a database question.** One app instance on a laptop served about 8,000 cached product reads per second. The same app fell to 26 requests per second on one bad query shape. Fix the queries before you buy hardware.
- **Read-heavy traffic** (catalogs, profiles, feeds: usually 90%+ of requests) is solved in layers: CDN and browser caches with `Cache-Control` and ETags, then an application cache, then read replicas (Level 5). Each layer adds staleness, so decide per field how stale is acceptable.
- **Hot keys.** One viral product can get more reads than one cache node can serve. Local caches on every instance spread that load naturally; with a shared Redis cache, one key on one Redis node becomes the bottleneck. Big systems keep a small local cache in front of the shared one.
- **Cache warm-up and cold starts.** After a deploy every instance starts with an empty cache, and the database gets the full read load at once. `sync = true` and rolling deploys (a few instances at a time) soften that.
- **Pagination at scale** is keyset almost everywhere. Exact totals over big tables are replaced by estimates (PostgreSQL's `reltuples`), by "1,000+", or by nothing.
- **Indexes at scale** are a write-cost budget: every one slows every insert. Teams review unused indexes (`pg_stat_user_indexes.idx_scan = 0`) and build new ones `CONCURRENTLY`.
- **Connections at scale**: pools stay small per instance, PgBouncer multiplexes them, and the database's `max_connections` is a hard limit to plan for, not a setting to raise.
- **Google or Meta scale** uses the same ideas with more layers. Caches are fleets of their own (Meta's TAO and Memcache papers describe invalidation across data centres). Sharding replaces one big table, and pagination tokens are opaque everywhere. What you built here is the same thing at the size of one service.

---

## 13. Interview talk track

"Product reads go through a Caffeine cache with cache-aside semantics: bounded, ten-minute expiry as a staleness bound, and `sync` loading so a hot key's expiry doesn't stampede the database. The cache proxy is ordered outside the transaction, so a hit doesn't borrow a connection, and eviction happens after the update commits. Because it's a per-instance cache, other instances can serve a stale product for up to the TTL; if that mattered I'd broadcast evictions or move to Redis. Responses carry the row version as an ETag, so clients revalidate with `If-None-Match` and get a 304, and updates can send `If-Match` and get 412 when they're stale. `@Version` stops two concurrent updates from silently overwriting each other. Large listings use keyset pagination with an opaque cursor and a row-value comparison that matches a composite index, which EXPLAIN shows as an index seek with no sort. Offset pagination plus `count(*)` collapsed to about 26 requests per second at a depth of 500,000 rows in my load test, while keyset stayed in the thousands. The connection pool is small and fixed with a 2-second timeout, so overload turns into fast 503s with `Retry-After` instead of hanging threads."

**Questions you should now be able to answer:**
1. Cache-aside: what happens on a hit, a miss and an update? Why evict instead of updating the cached value?
2. Why must the cache proxy sit outside the transaction? How would you prove it?
3. Name three ways a cached value goes stale, and what bounds each.
4. What is a cache stampede, and how do you prevent one?
5. Local cache vs Redis: when would you choose each?
6. What do ETag, `If-None-Match`, 304, `If-Match` and 412 do? `no-cache` vs `no-store` vs `max-age`?
7. Optimistic locking with `@Version`: what SQL does it produce, and what happens when it loses?
8. Why is offset pagination slow at depth, and what does keyset change? What do you give up?
9. Why can `(a, b) > (x, y)` use an index when the equivalent OR often cannot?
10. How do you read an EXPLAIN plan? What do `Index Cond`, `Filter` and `Rows Removed by Filter` tell you?
11. In which order do you put the columns of a composite index?
12. How big should a connection pool be, and why does bigger stop helping? What does PgBouncer solve?
13. Why return 503 with `Retry-After` when the pool is exhausted, and why fail after 2 seconds instead of 30?
