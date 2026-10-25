# Level 6: Production

Goal: know what it takes to RUN this service, not just build it. You should be able to answer three interview questions with specifics: "How would you know it is broken, and where?" (observability), "How does a new version reach users without downtime?" (deployment), and "Would you split this into microservices?" (architecture).

Read Levels 4 and 5 first: the pool, the cache, the outbox and the replica all reappear here as things to watch.

Versions: Spring Boot 4.1.2 (Micrometer 1.17, OpenTelemetry 1.62), Spring Modulith 2.1.2, Kubernetes API 1.33 for the manifests.

---

## 1. The picture

```
                 Ingress / load balancer
                         |
          Service "shop" (only READY pods get traffic)
          /              |               \
      pod 1            pod 2            pod 3          Deployment: rolling update, probes, preStop
   (container: layered image, non-root, JVM heap = 75% of the memory limit)
      |  logs (JSON, one object per line, trace.id in each)  -> log platform
      |  /actuator/prometheus  (pulled every 15-30 s)        -> Prometheus -> alerts, dashboards
      |  spans (OTLP push, 10% sampled)                       -> Jaeger / Tempo / any OTLP backend
      v
   PostgreSQL (primary + replicas)
```

---

## 2. What is new in the repo

| Path | What it is |
|---|---|
| `observability/TraceIdHeaderFilter.java`, `ObservabilityConfig.java` | `X-Trace-Id` on every response |
| `security/TokenSubject.java` | Breaks the auth <-> security cycle (section 6) |
| `order/OrderService.java` | Counts order outcomes as the metric `shop.orders` |
| `application.properties` | Health groups, tracing, histograms, graceful shutdown |
| `application-prod.properties` | JSON logs, 10% trace sampling (`SPRING_PROFILES_ACTIVE=prod`) |
| `Dockerfile` | Layered image, container-aware heap |
| `deploy/k8s/*.yaml` | Deployment, Service, HPA, PodDisruptionBudget, ConfigMap, example Secret |
| `.github/workflows/ci.yml` | Smoke test of the built image; manifest validation (`manifests` job) |
| `src/test/.../architecture/ModularityTest.java` | Spring Modulith: no cycles between modules |
| `pom.xml` | `spring-boot-starter-opentelemetry`, `micrometer-registry-prometheus`, Spring Modulith (test) |

---

## 3. Observability: logs, metrics, traces

Three signals, three questions:

| Signal | Answers | Cost |
|---|---|---|
| **Metrics** (numbers over time) | Is something wrong, and since when? How much, how fast? | cheap: a few numbers per instance every 15 s |
| **Logs** (events with text) | What exactly happened in this case? | grows with traffic |
| **Traces** (the path of one request through every component) | Where did the time go, and which hop failed? | most expensive, so usually sampled |

They become powerful when they are **connected**: an alert on a metric, then the traces from that minute, then the log lines of one bad trace. The thing that connects them here is the **trace id**.

### 3.1 Traces and the trace id

`spring-boot-starter-opentelemetry` instruments every HTTP request. Each request gets a trace: a tree of **spans** (one per unit of work, with start, duration and tags), identified by a 32-hex-character trace id.

- **Propagation.** If the caller sends a W3C `traceparent` header (`00-<trace id>-<parent span id>-01`), we continue its trace instead of starting a new one, so one id covers the whole path through every system. `ObservabilityTest.anIncomingTraceparentIsContinued` sends one and gets the same trace id back.
- **`X-Trace-Id`** on every response (`TraceIdHeaderFilter`), including 401/403, because the filter runs before Spring Security. A user who says "it failed" can give you that id.
- **Logs.** With tracing on, Spring Boot adds the trace and span ids to every log line written during the request: `[4bf92f3577b34da6a3ce929d0e0e4736-36c708e8d09b0721]` in the text format, `"traceId"` and `"spanId"` fields in JSON. Lines written outside a request (startup, background jobs) show an empty slot.
- **Export.** Spans are only sent if you configure an endpoint: `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://collector:4318/v1/traces`. OTLP is the OpenTelemetry protocol; Jaeger, Grafana Tempo, Honeycomb, Datadog and the OpenTelemetry Collector all accept it.
- **Sampling.** Recording every trace is fine locally (`management.tracing.sampling.probability=1.0`). The `prod` profile keeps 10%: enough to see patterns at a fraction of the storage. (Smarter "tail sampling" keeps every slow or failed trace and drops boring ones; it is done in the collector.)

### 3.2 Metrics

`/actuator/prometheus` (ADMIN only) serves every metric in Prometheus' text format. Prometheus **pulls** it every 15-30 seconds from each instance. A few lines of it:

```
http_server_requests_seconds_bucket{application="shop",method="GET",status="200",uri="/api/v1/ping",le="0.001"} 0
hikaricp_connections_active{application="shop",pool="shop-db"} 0.0
shop_orders_total{application="shop",outcome="created"} 1.0
```

What to measure, two checklists that come up in interviews:
- **RED, for every endpoint:** **R**ate (requests/s), **E**rrors (failed/s), **D**uration (latency distribution). `http.server.requests` gives all three.
- **USE, for every resource:** **U**tilization, **S**aturation (work waiting), **E**rrors. For the connection pool: `hikaricp.connections.active` vs `.max`, and `hikaricp.connections.pending`. Pending above zero is saturation: requests are queueing for the database (Level 4).

Rules worth knowing:
- **Averages lie.** 99 requests at 10 ms and one at 5 s average 60 ms, and the user who waited 5 s is invisible. Use percentiles: p95, p99. `management.metrics.distribution.percentiles-histogram.http.server.requests=true` publishes **histogram buckets**, from which Prometheus can compute a percentile across ALL instances: `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/v1/products/{id}"}[5m])))`. Percentiles computed inside each instance cannot be combined (an average of p99s is not a p99).
- **Business metrics.** `shop.orders{outcome=created|replayed|out_of_stock|rejected}` answers "can people still buy?" A drop in `created` while traffic is normal is an incident even when every request is fast and returns 200.
- **Cardinality.** Every distinct combination of tag values is a separate time series. Tag by `outcome` (4 values), never by user id or order id (millions of series will take down your metrics system). `uri` is the template (`/api/v1/products/{id}`), not the real path, for the same reason.
- **Pull vs push.** The OpenTelemetry starter can also PUSH metrics over OTLP. That is switched off (`management.otlp.metrics.export.enabled=false`) because nothing listens locally; without that line, the app logged a failed push every minute.

### 3.3 Logs

In the `prod` profile every log line is one JSON object (Elastic Common Schema):

```json
{"@timestamp":"2026-10-25T16:04:06.28Z","log":{"level":"INFO","logger":"dev.elsepiol.shop.ShopApplication"},"service":{"name":"shop","version":"0.0.1-SNAPSHOT"},"message":"Starting ShopApplication ...","ecs":{"version":"8.11"}}
```

A log platform (Loki, Elasticsearch, CloudWatch, Datadog) can then filter by field (`log.level:ERROR AND traceId:...`) instead of grepping text, and multi-line stack traces stay one event. The CI smoke test checks that every line the container writes to stdout is valid JSON.

What never goes in logs: passwords, tokens, full card numbers, and, depending on the law where you operate, personal data such as emails. We already avoid logging request bodies.

### 3.4 Health: liveness is not readiness

```
management.endpoint.health.group.liveness.include=livenessState
management.endpoint.health.group.readiness.include=readinessState,db
```

| Probe | Question | When it fails, Kubernetes... |
|---|---|---|
| liveness | Is this process broken beyond repair (deadlocked, out of memory)? | **restarts** the container |
| readiness | Can it serve requests right now? | stops sending it traffic until it is ready again |
| startup | Has it finished starting? | waits; liveness is not checked until then |

The database belongs in readiness only. If PostgreSQL is down and the database check were in liveness, Kubernetes would restart every pod, over and over, and turn a database outage into a restart storm that is still going after the database recovers. `ConnectionPoolTest` exhausts the pool and checks exactly this: readiness 503, liveness 200.

### 3.5 SLOs and alerts

An **SLI** is a measurement ("the share of product reads answered in under 200 ms"), an **SLO** a target for it ("99.5% over 30 days"), and the **error budget** what is left (0.5% of requests may be slow). Teams release freely while budget remains and slow down when it runs out.

Alert on **symptoms users feel** (error rate, latency SLO burn, `shop.orders{outcome=created}` dropping), not on every cause (one CPU at 90%). Causes go on dashboards for the person investigating. Every alert must be actionable, or people learn to ignore alerts. Examples in PromQL:

```
# more than 1% of requests failing over 5 minutes
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.01
# requests queueing for database connections
max(hikaricp_connections_pending{pool="shop-db"}) > 0
```

---

## 4. Deployment

### 4.1 The image

The Dockerfile now **extracts the jar into layers** with Spring Boot's `jarmode=tools`:

| Layer | Size | Changes |
|---|---:|---|
| `dependencies` (third-party libraries) | 70 MB | when `pom.xml` changes |
| `spring-boot-loader` | 676 KB | when Spring Boot is upgraded |
| `snapshot-dependencies` | almost empty | when snapshot libraries change |
| `application` (our code) | 572 KB | every commit |

Each is its own image layer, so a normal code change ships and pulls 572 KB, not 70 MB. Other details: the container runs as an unprivileged user; `-XX:MaxRAMPercentage=75` makes the heap 75% of the container's memory limit (the JVM's default would be 25%). The flags go in `ENTRYPOINT`, not in `JAVA_TOOL_OPTIONS`, because the JVM prints a "Picked up ..." note for that variable, and that note is not JSON.

**Tests prove the code; the smoke test proves the artifact.** CI now runs the image it just built against a real PostgreSQL with the `prod` profile, waits for READY (about 6 seconds in CI), calls `/api/v1/ping`, expects 401 from a protected endpoint, and checks that the logs are JSON. That would catch a broken Dockerfile, a missing environment variable or a migration that fails on a fresh database, none of which the unit tests can see.

### 4.2 Kubernetes

`deploy/k8s/` describes how the app runs on a cluster. The parts that matter in an interview:

- **Deployment, 3 replicas, rolling update with `maxSurge: 1`, `maxUnavailable: 0`.** A new pod starts, and only when it is READY does an old one go. Capacity never drops during a deploy. This only works because the app is stateless (Level 0): any pod can serve any request.
- **Probes** as in section 3.4, with a startup probe so that slow starts (Flyway migrations, JIT warm-up) are not mistaken for a dead process.
- **Resources.** `requests` is what the scheduler reserves; the memory `limit` is what the JVM sizes itself from. No CPU limit: CPU limits throttle a Java process unpredictably (garbage collection and JIT threads count too); many teams set only a request.
- **Security context.** Non-root, no privilege escalation, all Linux capabilities dropped, read-only root filesystem with a writable `/tmp` for Tomcat.
- **Config and secrets.** Settings come from a ConfigMap and a Secret as environment variables (the twelve-factor app style), so one image runs in every environment. `secret.example.yaml` is only an example. Real secrets come from a secrets manager; base64 in a YAML file is not encryption.
- **HorizontalPodAutoscaler**: 3 to 10 pods at 70% CPU. Check the arithmetic against the database: 10 pods × 10 connections = 100, PostgreSQL's default `max_connections` (Level 4).
- **PodDisruptionBudget**: during node maintenance at least 2 pods keep running.
- **Pin the image tag** to a version or commit SHA, never `:latest`. Then "what is running?" has an answer, and rolling back means deploying the previous tag.

CI validates every manifest against the Kubernetes 1.33 API schema with `kubeconform -strict`. A misspelled field like `readinesProbe` is rejected; without strict mode Kubernetes would silently ignore it, and the pod would run without a readiness probe.

### 4.3 Graceful shutdown, step by step

When Kubernetes replaces a pod:

1. The pod is marked terminating and removed from the Service's endpoints. That update takes a moment to reach every node.
2. Meanwhile `preStop` sleeps 5 seconds, so requests still being routed to this pod are served. (The `sleep` action needs Kubernetes 1.30 or newer; older clusters use an `exec` hook running `sleep 5`.)
3. Kubernetes sends SIGTERM. With `server.shutdown=graceful`, Tomcat stops accepting new connections and waits up to 20 s (`spring.lifecycle.timeout-per-shutdown-phase`) for running requests. Then the outbox poller stops (a batch in progress is either recorded or picked up again after its lease), and the connection pools close.
4. `terminationGracePeriodSeconds: 30` is longer than 5 + 20, so Kubernetes does not have to force-kill it.

Skip any step and every deploy produces a burst of connection errors that nobody can reproduce.

### 4.4 Database migrations during a rolling deploy

During a rolling deploy the old and the new version run **at the same time, against the same database**. So every migration must work with both the old code and the new code. The safe pattern is **expand, then contract**, spread over several releases. Renaming `name` to `title`:

1. Release 1 (expand): add `title`; the code writes both columns and reads `name`.
2. Backfill `title` for old rows (in batches).
3. Release 2: the code reads `title` and still writes both.
4. Release 3 (contract): stop writing `name`; a later migration drops it.

Look back at this project's migrations with that in mind. V4's `ADD COLUMN version ... DEFAULT 0` is safe: old code ignores the column, and since PostgreSQL 11 it does not rewrite the table. Exercise 4's `DROP INDEX` is safe for code but should be `DROP INDEX CONCURRENTLY` on a busy table. A plain `RENAME COLUMN` or `ADD COLUMN ... NOT NULL` without a default would break the old version mid-deploy.

With several pods starting at once, each runs Flyway; Flyway takes a database lock, so one migrates and the others wait. Bigger teams run migrations as a separate step (a Kubernetes Job) before the new pods roll out, so that a slow migration never blocks startup probes.

### 4.5 Getting it to users

What this repo's pipeline does today, and what a team would add:

| Stage | Here | Typically next |
|---|---|---|
| build, unit and integration tests | yes | |
| exercises verified, manifests validated | yes | |
| image built and smoke-tested | yes | push it to a registry (GHCR, ECR) tagged with the commit SHA |
| deploy | no | to staging automatically, run the smoke test there, then promote to production |
| release strategy | | rolling (what the manifests do), canary (1% of traffic first, watch the SLOs), or blue/green (switch everything at once, switch back to roll back) |
| rollback | | redeploy the previous image tag; migrations only go forward, which is why they must be expand/contract |

Feature flags separate **deploy** (code is on the servers) from **release** (users see it), so a risky feature can be switched off without a deploy.

---

## 5. Testing

| Test | Needs Docker | Proves |
|---|---|---|
| `ModularityTest` | no | no cycles between modules; `product` depends on nothing; writes module diagrams |
| `ObservabilityTest` | yes | `X-Trace-Id` everywhere, `traceparent` continued, Prometheus has histograms and order outcomes, scrape is ADMIN only |
| `ConnectionPoolTest` (new lines) | yes | pool exhausted: readiness 503, liveness 200 |
| CI "Smoke test the image" | Docker in CI | the image starts with the prod profile, becomes ready, logs JSON |
| CI `manifests` job | no | the Kubernetes YAML is valid, strictly |

One more test lesson: Spring Boot tests switch metric exporters and trace propagation **off** by default, so `/actuator/prometheus` is a 404 and incoming `traceparent` headers are ignored in an ordinary `@SpringBootTest`. That is the first thing you run into when you test observability. `@AutoConfigureMetrics` and `@AutoConfigureTracing` switch them on for the test class that needs them.

And one test that had to change: `AuthApiTest` checked that "wrong password" and "unknown email" give byte-identical bodies (Level 2, no user enumeration). Once error bodies carry a trace id (Exercise 6), two responses are never byte-identical, so the test now compares them without the per-request id. The guarantee it protects is unchanged.

---

## 6. When to split into services

### 6.1 A modular monolith first

Each package under `dev.elsepiol.shop` is a module. `ModularityTest` uses Spring Modulith to read the compiled classes and check that the modules form a graph without cycles:

```
error -> auth, inventory, order, product          notification -> order, outbox
order -> inventory, outbox, product               inventory -> product
security -> ratelimit                             auth -> security
product, outbox, ratelimit, cache, db, observability, ping -> nothing
```

Writing this test found a real cycle: `auth` used `security` to issue tokens, and `security` took `auth`'s `User` entity to read the claims. Neither module could ever be moved or changed on its own. The fix was small: `security` now defines `TokenSubject` (just subject, email, role) and `auth` fills it in. When the test fails, it says `Cycle detected: Slice auth -> Slice security -> Slice auth`. Run it, and look at the diagrams it writes to `target/spring-modulith-docs`.

A monolith with enforced module boundaries gives most of what people want from microservices (code you can reason about one module at a time, clear ownership) without the network in between. It also keeps the option to split later, because the seams already exist.

### 6.2 When splitting is worth it

| Good reasons | Bad reasons |
|---|---|
| A part needs to scale very differently (the outbox workers, image processing) | "Microservices are modern" |
| Different teams need to deploy independently, often (Conway's law: systems mirror the teams that build them) | The codebase is messy (a messy monolith becomes a messy distributed system) |
| A part has different reliability or security needs (payments) | One team, one deploy pipeline anyway |
| A part needs a different technology (search engine, ML) | |

What you pay: every call can now fail or time out (you need timeouts, retries with backoff, circuit breakers); a transaction can no longer cover two services, so consistency comes from events and idempotency (exactly Level 5's outbox); every service needs its own deploys, monitoring and on-call; and debugging needs distributed tracing (section 3.1).

### 6.3 How you would do it here

- **Each service owns its data.** No other service reads its tables. Shared tables couple deploys more tightly than shared code.
- **Notifications is the natural first candidate**: it already only reacts to events. The outbox relay would publish `OrderPlaced` to Kafka instead of calling `OrderPlacedNotifier` in-process, and a notification service would consume it with the same idempotent handler.
- **Product catalog next**: read-heavy, depends on nothing (the test checks that), easy to cache and scale on its own. Orders would keep a copy of what they need (name, price at purchase time, which they already snapshot).
- **Strangler fig**: put a gateway in front, move one route at a time to the new service, delete the old code when nothing calls it. Never a big-bang rewrite.

---

## 7. Run it yourself

```
docker compose up -d db
SPRING_PROFILES_ACTIVE=prod BOOTSTRAP_ADMIN_EMAIL=admin@example.com BOOTSTRAP_ADMIN_PASSWORD=local-admin-password ./mvnw spring-boot:run
```

JSON logs in the console. Then:

```
curl -si localhost:8080/api/v1/products/999 | grep -i x-trace-id
curl -si localhost:8080/api/v1/ping -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" | grep -i x-trace-id
curl -s localhost:8080/actuator/health/liveness; curl -s localhost:8080/actuator/health/readiness
curl -s localhost:8080/actuator/prometheus -H "Authorization: Bearer $ADMIN" | grep -E "^shop_orders|hikaricp_connections_(active|pending)"
docker build -t shop:local . && docker images shop:local
```

Stop the database (`docker compose stop db`) and ask both health endpoints again. Which one changed, and what would Kubernetes do?

If you have a local cluster (kind, minikube, Docker Desktop), `deploy/k8s/` is a starting point: build the image, load it into the cluster, point `DB_URL` in the ConfigMap at a database the cluster can reach, create the Secret, then `kubectl apply -f deploy/k8s/` and watch `kubectl get pods -w`. These steps were not run while writing this level (the manifests were only validated with kubeconform), so expect to adapt them to your cluster.

**Break it on purpose:**
1. Add `db` to the liveness group, stop the database, and think through what Kubernetes would do to all three pods.
2. Remove `@AutoConfigureTracing` from `ObservabilityTest` and run it. Which test fails, and why?
3. Make `JwtService.issue` take `User` again and run `ModularityTest`.
4. Misspell a field in `deployment.yaml` and run kubeconform (command in `.github/workflows/ci.yml`), with and without `-strict`.
5. Tag a metric with the user id, place orders as 50 users, and count the series in `/actuator/prometheus`. Now imagine 1,000,000 users.

---

## 8. Exercise 6: watch the queue, and put the trace id in errors

Needs Docker running.

**Part A: outbox backlog gauges.** Level 5 said "monitor queue depth and age, or you find out from customers". Add three gauges:
- `shop.outbox.pending`: number of `PENDING` events
- `shop.outbox.dead`: number of `DEAD` events
- `shop.outbox.oldest.pending.age`: seconds since the oldest `PENDING` event was created (0 when there is none)

One new class in `outbox` implementing Micrometer's `MeterBinder` (a Spring bean; Spring Boot binds every `MeterBinder` to the registry). A gauge does not store a value: its function runs each time the gauge is read, so keep the queries cheap. Which index makes the pending count cheap?

**Part B: trace id in every error body.** Every problem-details error (404, 400, 409, 503...) should include `"traceId"`, the same id as the `X-Trace-Id` header. If the caller sent a `traceparent`, it is that trace's id. Do not edit every `@ExceptionHandler`: one `@RestControllerAdvice` implementing `ResponseBodyAdvice` can add a property to any `ProblemDetail` just before it is written.
- Watch out: `@WebMvcTest` slices load `@RestControllerAdvice` classes but have no tracing. If your class requires a `Tracer`, the Level 0 exercise test breaks. `ObjectProvider<Tracer>` is the way out.
- The 401/403 bodies are written by `SecurityProblemHandlers` without Spring MVC, so they are not covered. Should they be? How would you do it?

```
./mvnw test -Pexercises
```

Done when the Level 6 exercise tests pass and `./mvnw verify` still passes (`AuthApiTest` already ignores the per-request id, see section 5).

**Read first** (search the page title if a link has moved):
- Spring Boot, observability (tracing, metrics, logging): https://docs.spring.io/spring-boot/reference/actuator/observability.html
- Spring Boot, Kubernetes probes: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes
- Micrometer, gauges: https://docs.micrometer.io/micrometer/reference/concepts/gauges.html
- W3C Trace Context: https://www.w3.org/TR/trace-context/
- Kubernetes, liveness, readiness and startup probes: https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/
- Spring Modulith, verifying application module structure: https://docs.spring.io/spring-modulith/reference/verification.html
- Google SRE book, "Service Level Objectives": https://sre.google/sre-book/service-level-objectives/

**Answer in your own words:**
1. Why age AND depth? Describe a situation where depth looks fine and age shows the problem.
2. With 10 instances each scraped every 15 s, how many times per minute do your gauge queries run? When would you compute the values once in the background instead?
3. What alert would you write on these gauges, and what should the person who gets paged do first?
4. Why must a gauge never be tagged with an event id?

**Stretch (no tests):**
- Add a custom span: wrap `OutboxWorker.runOnce` in an `Observation` so each batch shows up in traces with the number of events as a tag.
- Write a Prometheus alerting rule file for the two PromQL examples in section 3.5 and validate it with `promtool check rules`.
- Add a `Job` manifest that runs Flyway migrations before the Deployment rolls out, and think about what happens if it fails halfway.

Reference solution: `solutions/level-6/` (`outbox/OutboxMetrics.java`, `observability/TraceIdInProblemDetails.java`). Compare after you try.

---

## 9. The scaling lens (Level 6)

- **Small company, one team**: this setup is enough. One modular monolith, 3 to 10 pods, managed PostgreSQL with a replica, Prometheus and Grafana (or a hosted equivalent), logs and traces in one platform. The biggest risks are operational: untested deploys, missing alerts, migrations that lock tables.
- **Growing company, several teams**: modules get owners; the first services split along team boundaries; an event backbone (Kafka) replaces in-process handlers; a platform team owns the cluster, CI/CD templates and observability stack, so product teams do not each reinvent them.
- **Google or Meta scale**: thousands of services; every request carries trace context across all of them; metrics in purpose-built time-series systems (Google's Monarch); deploys are automated canaries judged by SLO checks, not by people; capacity planning is its own discipline. The vocabulary in this lesson (SLOs, error budgets, canaries, liveness vs readiness, expand/contract) is the vocabulary those companies use.

---

## 10. Interview talk track

"In production it runs as a stateless container on Kubernetes: a layered image, non-root, heap sized from the memory limit, three replicas behind a Service with rolling updates that never reduce capacity. Liveness checks only the process, readiness includes the database, so a database outage stops traffic instead of restarting everything. Shutdown is graceful: preStop, then Tomcat drains for up to 20 seconds inside a 30-second grace period. CI tests the code, then smoke-tests the actual image against PostgreSQL with the prod profile, and validates the manifests strictly. For observability, every request gets an OpenTelemetry trace id that is propagated with W3C traceparent, written into JSON logs and returned as X-Trace-Id. Prometheus scrapes latency histograms, so I can compute p99 across instances, plus pool saturation, cache hits, a business counter of order outcomes, and the outbox backlog. Migrations follow expand-and-contract because old and new versions overlap during a deploy. The code is a modular monolith whose module graph is checked for cycles in a test; I'd split out notifications first, since it already communicates only through events."

**Questions you should now be able to answer:**
1. Logs, metrics, traces: what does each answer, and how do you connect them?
2. What is a trace, a span, and how does a trace id travel between services?
3. Why are averages misleading, and why do you need histograms to get a p99 across instances?
4. RED vs USE: which metrics would you put on this service's dashboard?
5. What is metric cardinality, and how do you blow it up by accident?
6. Liveness vs readiness vs startup probes, and why the database must not be in liveness.
7. What are an SLI, an SLO and an error budget? What makes a good alert?
8. How does a rolling update avoid downtime? What do `maxSurge` and `maxUnavailable` do?
9. Walk through graceful shutdown in Kubernetes. What goes wrong without `preStop`?
10. Why must migrations be backwards compatible during a deploy? Explain expand/contract with an example.
11. Rolling vs canary vs blue/green: trade-offs?
12. Monolith vs modular monolith vs microservices: when would you split, and what does splitting cost?
