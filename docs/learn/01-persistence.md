# Level 1: Persistence

Goal: store data in a real database and understand every layer between an HTTP request and a row on disk. You will also meet the first scaling facts that interviewers ask about: connection pools, pagination and indexes.

Read Level 0 first. Same method: read a section, run the matching command, break something on purpose, then do the exercise.

---

## 1. The picture

```
HTTP request  POST /api/v1/products
   |
ProductController      HTTP only: parse JSON, validate, choose status code
   |
ProductService         business rules, transactions
   |
ProductRepository      "save / find" interface (Spring Data JPA generates it)
   |
Hibernate (the JPA implementation)   turns objects into SQL
   |
HikariCP connection pool -> PostgreSQL JDBC driver
   |
PostgreSQL
```

Why layers: each one has a single reason to change. A new URL touches the controller. A new business rule touches the service. A new database touches almost nothing else. It also makes testing cheap (section 11).

---

## 2. What is new in the repo

| Path | What it is |
|---|---|
| `src/main/resources/db/migration/V1__create_products.sql` | The schema, as a versioned migration |
| `product/Product.java` | The entity: one object = one row |
| `product/ProductRepository.java` | Database access, as an interface |
| `product/ProductService.java` | Business logic and transactions |
| `product/ProductController.java` | The HTTP endpoints |
| `product/CreateProductRequest.java`, `ProductResponse.java`, `PageResponse.java` | The API's JSON shapes (DTOs) |
| `product/*Exception.java`, `error/ApiExceptionHandler.java` | Turning failures into proper HTTP errors |
| `Dockerfile`, `docker-compose.yml`, `.dockerignore` | Running the app and a database in containers |
| `src/test/.../support/PostgresIntegrationTest.java` | Starts a real PostgreSQL for tests |
| `pom.xml` | Gained JPA, Flyway, the PostgreSQL driver and Testcontainers |
| `application.properties` | Gained datasource, JPA and paging settings |

---

## 3. Schema migrations with Flyway

A database schema changes over time, and every environment (your laptop, CI, production) must end up identical. A migration tool solves this: each change is a numbered SQL file, applied once, in order, and recorded.

- On startup Flyway looks in `db/migration`, compares with a table called `flyway_schema_history`, and runs only the files not yet applied.
- **Never edit a migration that has been applied anywhere.** Flyway stores a checksum and will refuse to start if the file changed. To change the schema, add `V2__...sql`.
- `spring.jpa.hibernate.ddl-auto=validate` means Hibernate does not create tables; it only checks that entities match the tables and fails at startup if not. Flyway owns the schema. (Letting Hibernate generate production schemas is a classic mistake.)

Read `V1__create_products.sql` and notice: `BIGINT GENERATED ALWAYS AS IDENTITY` (database-assigned id), `NOT NULL` and `CHECK (price_cents >= 0)` (rules the database enforces even if our code has a bug), `UNIQUE (sku)` (explained in section 8), `TIMESTAMPTZ` (a timestamp with time zone; store times in UTC).

---

## 4. JPA, Hibernate and Spring Data

Three names that get confused:
- **JPA** is a specification (annotations and interfaces such as `@Entity`).
- **Hibernate** is the library that implements JPA and generates SQL.
- **Spring Data JPA** sits on top and writes repository implementations for you.

**The entity.** `@Entity` + `@Table` map the class to the `products` table. `@Id` marks the primary key. `@GeneratedValue(strategy = IDENTITY)` means the database assigns the id on insert. JPA requires a no-argument constructor; ours is `protected` so only the framework uses it.

**The repository.** `ProductRepository` is an interface with no code. At startup Spring Data generates the implementation. `JpaRepository<Product, Long>` already provides `save`, `findById`, `findAll(Pageable)`, `delete` and more. Methods named by convention become queries: `existsBySku(String sku)` becomes `select exists(select 1 from products where sku = ?)`.

To see the SQL Hibernate produces, add `spring.jpa.show-sql=true` to `application.properties` and watch the console. Always look at the SQL your code generates; ORMs hide it, and that is where performance problems come from.

---

## 5. Transactions

A transaction groups database operations so they all succeed or all fail (atomicity), and keeps concurrent users from seeing half-finished work (isolation). `@Transactional` on a service method makes Spring open a transaction before the method and commit when it returns normally, or roll back if a runtime exception escapes.

- `readOnly = true` on read methods is a hint that lets the database and Hibernate skip work.
- It works through a proxy: Spring wraps your service in a generated subclass. Consequence to remember: calling a `@Transactional` method from another method of the same class bypasses the proxy and the annotation is ignored. This is a favourite interview question.

---

## 6. DTOs, validation and money

- **DTO** (data transfer object): `CreateProductRequest` and `ProductResponse` are separate from the `Product` entity. The API contract and the table can evolve independently, and a client can never set `id` or `createdAt`.
- **Records** are perfect for DTOs: immutable, no boilerplate.
- **Validation:** annotations such as `@NotBlank`, `@Size`, `@Pattern`, `@PositiveOrZero` on the record, plus `@Valid` on the controller parameter. A failed check never reaches the service.
- **Money is stored as whole minor units** (`priceCents`, a `long`). Floating point cannot represent 0.1 exactly, so never store money in `double`.

---

## 7. Errors that clients can use

`ApiExceptionHandler` is a `@RestControllerAdvice`: one place that converts exceptions into responses, in the standard **problem details** format (RFC 9457, `application/problem+json`):

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"A product with SKU MUG-001 already exists"}
```

Status codes to know and why we chose them:
- `201 Created` + a `Location` header pointing to the new resource (not plain 200)
- `400` the request itself is invalid (our validation adds an `errors` map of field to message)
- `404` the id does not exist
- `409 Conflict` the request is valid but clashes with current state (duplicate SKU)

Exceptions are a clean way to leave the service early; the handler is the single place that knows about HTTP.

---

## 8. A race condition you can explain

In `ProductService.create` we first call `existsBySku` and then insert. Two requests with the same SKU can both pass the check at the same instant and both try to insert. Check-then-act is not safe by itself.

The `UNIQUE (sku)` constraint in the database is the real guard: the second insert fails, and we catch `DataIntegrityViolationException` and return the same 409. The early check only exists to give a friendlier fast path. Rule: **enforce invariants in the database, not only in application code**, because the database is the one place all requests meet.

`ProductServiceTest.createTurnsALostRaceIntoTheSameDuplicateError` simulates this.

---

## 9. Pagination

`GET /api/v1/products?page=0&size=20` returns one page. `Pageable` is resolved from the query string, `@PageableDefault(size = 20, sort = "id")` sets defaults, and `spring.data.web.pageable.max-page-size=100` stops a client asking for a million rows. We return our own `PageResponse` shape instead of Spring's `Page`, because the shape of a public API should be ours to keep stable.

Under the hood this is `select ... order by id limit 20 offset 0` plus a `select count(*)` for the totals. Remember the `count(*)` and the offset; both come back in Level 4.

---

## 10. Docker

You need Docker (Docker Desktop on Windows) for the commands below and for the database tests.

- **Dockerfile** has two stages. Stage 1 uses a JDK image to build the jar. Stage 2 copies only the jar into a small JRE image and runs it as a non-root user. The final image has no source code and no build tools. Copying `pom.xml` and downloading dependencies before copying `src` lets Docker cache that slow step.
- **docker-compose.yml** describes the local setup: a `db` service (PostgreSQL with a health check and a named volume so data survives restarts) and an `app` service that waits for the database to be healthy.
- The passwords in these files are for local development only. In real environments they come from environment variables or a secrets manager, which is why `application.properties` reads `${DB_PASSWORD:shop}`.

---

## 11. Testing, by layer

| Test | Starts | Needs Docker | Speed | Proves |
|---|---|---|---|---|
| `ProductServiceTest` | nothing (Mockito fake repository) | no | milliseconds | our business logic |
| `ProductControllerTest` | web layer only (`@WebMvcTest`), service mocked | no | fast | URLs, JSON, validation, status codes |
| `ProductRepositoryTest` | JPA only (`@DataJpaTest`) + real PostgreSQL | yes | seconds | mappings, queries, constraints |
| `ProductApiTest`, `ShopApplicationTests` | everything + real PostgreSQL | yes | seconds | the layers work together, Flyway runs |

**Testcontainers** starts a throwaway PostgreSQL container for the tests and removes it afterwards, so tests run against the real database engine, not an in-memory imitation with different behaviour. `@ServiceConnection` wires the application to it automatically. Without Docker running, those tests are skipped (not failed). GitHub CI always has Docker, so it runs them all.

---

## 12. Run it yourself

```
docker compose up -d db                 # start PostgreSQL
./mvnw spring-boot:run                  # start the app (Windows: mvnw.cmd)
```

Create and read a product (PowerShell shown first, then curl):

```
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/products -ContentType application/json -Body '{"sku":"MUG-001","name":"Blue Mug","priceCents":49900}'
Invoke-RestMethod http://localhost:8080/api/v1/products

curl -i -X POST localhost:8080/api/v1/products -H "Content-Type: application/json" -d '{"sku":"MUG-001","name":"Blue Mug","priceCents":49900}'
curl localhost:8080/api/v1/products?size=5
```

Look inside the database:

```
docker compose exec db psql -U shop -d shop -c "select * from products;"
docker compose exec db psql -U shop -d shop -c "select * from flyway_schema_history;"
```

Run everything in containers instead: `docker compose up --build`.

**Break it on purpose:**
1. Send the same SKU twice. Which response do you get, and which line of code produced it?
2. Send `{"sku":"bad sku!","name":"","priceCents":-5}`. Read the `errors` map.
3. Edit `V1__create_products.sql` (add a space), restart. Read the Flyway error about the checksum.
4. Rename the `name` field in `Product.java` to `title`, restart. Read the Hibernate validation error.
5. Add `spring.jpa.show-sql=true`, call the list endpoint, and find the `limit` and `count` queries.

---

## 13. Exercise 1: delete and search

Needs Docker running. Add two endpoints:

**Part A: `DELETE /api/v1/products/{id}`**
- existing id: respond `204 No Content`; a following `GET` must return 404
- unknown id: respond `404` (reuse the exception that already exists)

**Part B: `GET /api/v1/products/search?q=mug`**
- returns products whose name contains `q`, ignoring case
- paged exactly like the list endpoint, same `PageResponse` shape
- no matches: a normal 200 with an empty page, not an error

You touch three files: the repository, the service and the controller. Follow the same patterns already in them.

```
./mvnw test -Pexercises
```

Done when the Level 1 exercise tests pass and `./mvnw verify` still passes.

**Read first** (search the page title if a link has moved):
- Spring Data JPA query methods: https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html
- Spring transactions: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Testcontainers: https://java.testcontainers.org/

**Answer in your own words:**
1. How does Spring Data turn a method name into a query? What would `findByNameStartingWithIgnoreCase` do?
2. Should `delete` be `@Transactional`? What would go wrong without it if you did two things in it?
3. What SQL does your search produce (use `show-sql`)? Will it use an index? Why or why not?

**Stretch (parallel component, no tests):** add `PUT /api/v1/products/{id}` to update a product. Then add a `@Version` field to the entity (needs a V2 migration adding a `version` column) and find out what **optimistic locking** does when two users edit the same product at once. You will use this idea in Level 3.

Reference solution: `solutions/level-1/solution.txt`. Compare after you try.

---

## 14. The scaling lens (Level 1)

**The first real limit is the database connection pool.** Spring Boot's pool (HikariCP) defaults to 10 connections. A request that needs the database holds a connection for the duration of its queries. A rough capacity estimate (Little's law): throughput is about the number of connections divided by time per query. 10 connections at 5 ms per query is roughly 2,000 queries per second at best, and real systems get less. So "what if 1,000 requests per second arrive" is answered by the pool size, query time and indexes, long before CPU. More connections is not free: PostgreSQL handles a few hundred connections well, not thousands.

**Search is slow by design right now.** `lower(name) like '%mug%'` cannot use a normal index, so PostgreSQL scans the whole table. Fine for 1,000 rows, painful for 10 million. Level 4 covers indexes, trigram search and when to use a search engine.

**Offset pagination degrades.** `offset 1000000` still reads and discards a million rows. Large systems use keyset pagination ("give me 20 rows after id X"). Also `count(*)` on a huge table is slow, which is why big sites often drop exact totals.

**Statelessness still holds.** All state is in PostgreSQL, so you can run several app instances behind a load balancer sharing one database. The database is then the single shared bottleneck, which is where read replicas and caching come in (Levels 4 and 5).

---

## 15. Interview talk track

"The service is layered: controller for HTTP, service for business rules and transactions, repository for data access through Spring Data JPA on PostgreSQL. Schema changes go through Flyway migrations and Hibernate only validates. Requests and responses are separate DTOs with validation, and errors come back as RFC 9457 problem details with correct status codes. Uniqueness is enforced by a database constraint because a check in code is racy. Tests are layered: Mockito unit tests, web and data slices, and end-to-end tests against real PostgreSQL via Testcontainers, all run in CI along with a Docker image build."

**Questions you should now be able to answer:**
1. JPA vs Hibernate vs Spring Data JPA?
2. Why Flyway instead of letting Hibernate create tables?
3. What does `@Transactional` do, and when does it silently not work?
4. Why are DTOs separate from entities?
5. How do you prevent duplicate rows under concurrent requests?
6. Why Testcontainers instead of an in-memory database?
7. What limits throughput first, and how would you estimate it?
8. What are the downsides of offset pagination?
