# spring-backend-ai

A learning-first backend project: an **orders and inventory** service (Spring Boot, Java 21, Maven) with a small React frontend added later. The point is not only the finished system. It is to learn how every part works well enough to explain it in an interview, by building it level by level.

## How this repo works

Each level has:
1. **A feature** already built and tested.
2. **A lesson** in `docs/learn/` explaining every file, framework component and design decision involved.
3. **An exercise**: failing tests that you make pass by writing the code yourself, using the documentation links in the lesson.
4. **A scaling lens**: what breaks as traffic grows, and what companies do about it.

## Levels

| Level | Topic | Status |
|---|---|---|
| 0 | [Orientation](docs/learn/00-orientation.md): build tool, build agent, project layout, how Spring starts, one request end to end | Done |
| 1 | [Persistence](docs/learn/01-persistence.md): PostgreSQL, JPA, Flyway migrations, layers, error handling, Testcontainers, Docker | Built (needs Docker to run the DB tests) |
| 2 | [Security](docs/learn/02-security.md): bcrypt passwords, JWT login, role-based access, 401 vs 403, deny by default | Built (needs Docker for the DB tests) |
| 3 | Correctness under concurrency: orders, stock, transactions, idempotency | Planned |
| 4 | Thousands of requests per second: caching, indexes, pagination, connection pools | Planned |
| 5 | Hundreds of thousands of users: queues and async work, rate limiting, read replicas | Planned |
| 6 | Production: observability, deployment, when to split into services | Planned |
| FE | Small React frontend: how the browser, API, tokens and CORS fit together | Planned |

## Quick start

Requires JDK 21. On Windows use `mvnw.cmd` instead of `./mvnw`.

```
./mvnw verify                  # build and run tests
docker compose up -d db        # start PostgreSQL (Level 1 onward; needs Docker)
./mvnw spring-boot:run         # run the app on http://localhost:8080
./mvnw test -Pexercises        # run the exercise tests (they fail until you do the exercise)
```

Then open `docs/learn/00-orientation.md`.

Each lesson's exercise has a reference solution under `solutions/level-N/`. CI proves every exercise fails without its solution and passes with it, so if one is broken CI says so.
