# Level 0: Orientation

Goal: after this level you can explain what every file in the repo is, how the app is built and started, and how one HTTP request travels through it. You will also write your first piece of the app yourself.

How to use this document: read a section, run the matching command, then try to break it (rename something, remove an annotation, read the error). Errors are the fastest teacher.

Versions used: Java 21, Spring Boot 4.1.2 (Spring Framework 7, Tomcat 11, Jackson 3), Maven. Numbers quoted below (startup time, jar size) were measured on the machine that built this and will differ on yours.

---

## 1. The 60-second picture

```
browser / React app / curl
        |  HTTP request  GET /api/v1/ping
        v
  Tomcat (web server, embedded inside our jar)
        v
  Spring MVC (finds the right method for the URL)
        v
  our code: PingController.ping()
        v
  Jackson (turns the Java object into JSON)
        v
  HTTP response  {"status":"ok","service":"shop"}
```

There is no separate web server to install. Our build produces one runnable `.jar` file that contains our code, Spring, Tomcat and every library. You start it with `java -jar`. Later levels add a database, a cache and a message queue around this same core.

---

## 2. Tour of every file

| Path | What it is | Why it exists |
|---|---|---|
| `pom.xml` | The Maven build description | Says what the project is, which libraries it needs and how to build it |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Maven Wrapper (Linux/Mac script, Windows script, config) | Downloads and runs the exact Maven version the project expects, so you do not need Maven installed |
| `src/main/java/dev/elsepiol/shop/ShopApplication.java` | The entry point with `main()` | Starts Spring |
| `src/main/java/.../ping/PingController.java` | Our first feature | Answers `GET /api/v1/ping` |
| `src/main/resources/application.properties` | Configuration | Settings that change without recompiling (app name, which actuator endpoints are exposed) |
| `src/test/java/...` | Tests | Prove the code works; run on every build |
| `src/test/java/.../exercises/` | Your exercise tests | Tagged `exercise`, skipped by the normal build |
| `solutions/` | Reference solutions, stored as `.txt` | Peek only after you have tried (they are not compiled) |
| `.github/workflows/ci.yml` | Continuous integration config | Tells GitHub how to build and test every push |
| `target/` | Generated build output (not in git) | Compiled classes, test reports, the final jar |
| `.gitignore`, `.gitattributes` | Git housekeeping | Keep generated files out of git; keep line endings consistent |
| `docs/` | These lessons | You are here |

Why the package is `dev.elsepiol.shop`: Java convention is a reversed domain name, then the project name. It matters later because Spring scans this package and everything below it for components (section 5).

---

## 3. The build tool: Maven

A build tool turns source code into something runnable and runs your tests. Maven describes the project in `pom.xml` and follows fixed conventions, so most Maven projects look alike.

**Coordinates.** `groupId` (`dev.elsepiol`) + `artifactId` (`shop`) + `version` (`0.0.1-SNAPSHOT`) uniquely name this project, exactly as every library you depend on is named. `SNAPSHOT` means "work in progress, not a released version".

**The parent.** `spring-boot-starter-parent` supplies default settings and, most importantly, a list of tested-together versions of hundreds of libraries. That is why the dependencies in our pom have no version numbers: the parent chooses them. Upgrading Spring Boot means changing one number.

**Starters.** A starter is a dependency that pulls in a whole set of libraries for one purpose:
- `spring-boot-starter-webmvc`: Spring MVC, embedded Tomcat, Jackson (JSON)
- `spring-boot-starter-actuator`: health and monitoring endpoints
- `spring-boot-starter-validation`: the Jakarta Bean Validation annotations (`@Size`, `@NotBlank`, ...)
- the `*-test` starters: JUnit, Mockito, MockMvc and Spring's test support (scope `test`, so they are not in the final jar)

**The lifecycle.** Maven runs phases in order; asking for a later phase runs all earlier ones:

| Phase | What happens | Output |
|---|---|---|
| `compile` | `.java` to `.class` | `target/classes` |
| `test` | Runs unit tests (Surefire plugin) | `target/surefire-reports` |
| `package` | Builds the jar (Spring Boot plugin repackages it into one self-contained "fat jar") | `target/shop-0.0.1-SNAPSHOT.jar` (about 23 MB here) |
| `verify` | Final checks | |

So `./mvnw verify` = compile, test, package, check. This is the single command CI runs.

**The profile `exercises`.** At the bottom of the pom, a profile switches Surefire to run only tests tagged `exercise`. Default build: exercises skipped. `-Pexercises`: only exercises run.

Interview angle: Maven vs Gradle. Maven is declarative and convention-heavy (predictable, verbose XML). Gradle is script-based and more flexible (faster incremental builds, steeper learning). Both are common; say which you used and why.

---

## 4. The build agent (CI)

A build agent is a machine that builds and tests your code automatically. Here it is a GitHub-hosted Ubuntu machine, configured by `.github/workflows/ci.yml`:

1. `on:` says when to run (a push, or a pull request).
2. `actions/checkout` copies the repo onto the fresh machine.
3. `actions/setup-java` installs Java 21 and caches downloaded libraries.
4. `./mvnw -B verify` runs the same command you run locally.

Why it matters: "it works on my machine" stops being an argument. If the clean machine cannot build it, it is broken. In companies this stage grows to include static analysis, security scans, building container images and deploying. You will add some of those in later levels.

Where to look: your repo, "Actions" tab, click a run, click a step to see its log.

---

## 5. How Spring Boot starts

`ShopApplication.main()` calls `SpringApplication.run(...)`. This is what happens, and you can see the real log lines when you run the app:

```
Starting ShopApplication v0.0.1-SNAPSHOT using Java 21 ...
Tomcat initialized with port 8080 (http)
Exposing 2 endpoints beneath base path '/actuator'
Tomcat started on port 8080 (http) with context path '/'
Started ShopApplication in 3.952 seconds
```

1. **An ApplicationContext is created.** This is the container that owns all the objects ("beans") of your app.
2. **Component scanning.** Spring walks the `dev.elsepiol.shop` package tree looking for classes annotated `@Component` or its specializations (`@RestController`, `@Service`, `@Repository`). It finds `PingController` and creates one instance.
3. **Dependency injection.** `PingController`'s constructor asks for a `String` (the app name). Spring supplies it from `application.properties`. You never write `new PingController(...)`; Spring does. This is **inversion of control**: the framework builds and wires objects instead of your code doing it.
4. **Auto-configuration.** Spring Boot looks at what is on the classpath. Tomcat and Spring MVC are there, so it configures a web server, the request dispatcher and JSON conversion for you. Actuator is there, so it adds the health endpoint.
5. **Tomcat starts** and listens on port 8080.

`@SpringBootApplication` on `ShopApplication` is three annotations in one:
- `@SpringBootConfiguration`: this class can define beans
- `@EnableAutoConfiguration`: switch on step 4
- `@ComponentScan`: switch on step 2 (scans from this class's package downward, which is why feature packages must live under `dev.elsepiol.shop`)

---

## 6. The life of one request

`GET http://localhost:8080/api/v1/ping`

1. Tomcat accepts the connection and parses the HTTP request. It takes a thread from its pool (200 by default) to handle it.
2. The request goes to Spring's `DispatcherServlet`, the single "front controller" every request passes through.
3. A handler mapping looks up which method serves `GET /api/v1/ping`. It finds `PingController.ping()` because of `@RequestMapping("/api/v1/ping")` on the class plus `@GetMapping` on the method.
4. Spring calls the method (resolving any parameters first; here there are none).
5. The method returns a `PingResponse` record.
6. Because the class is a `@RestController`, the return value is written as the response body. An HTTP message converter (Jackson) turns the record into `{"status":"ok","service":"shop"}` and sets `Content-Type: application/json`.
7. Tomcat sends the response and returns the thread to the pool.

If anything throws, Spring turns the exception into an error response instead. You will customize that in Level 1.

---

## 7. Annotations used so far

| Annotation | Meaning |
|---|---|
| `@SpringBootApplication` | Entry point: config + auto-config + component scan |
| `@RestController` | A class whose methods handle web requests and return data (not HTML pages) |
| `@RequestMapping("/api/v1/ping")` | Base URL for the class |
| `@GetMapping` | This method handles HTTP GET |
| `@Value("${spring.application.name}")` | Inject a configuration value |
| `@PathVariable` (Exercise 0) | Take a value from the URL path |
| `@Size`, `@Pattern` (Exercise 0) | Validation rules on a parameter |
| `@WebMvcTest` | Test only the web layer |
| `@SpringBootTest` | Test with the whole application context |
| `@Tag("exercise")` | Label a test so builds can include or skip it |

`/api/v1/` in the URL is deliberate API versioning: a future incompatible change can ship as `/api/v2/` without breaking existing clients.

---

## 8. Actuator and health checks

`GET /actuator/health` returned `{"groups":["liveness","readiness"],"status":"UP"}` when this was run. Load balancers and Kubernetes poll an endpoint like this to decide whether to send traffic to an instance and whether to restart it. As we add a database, health will also report whether the database is reachable. That is why `management.endpoints.web.exposure.include=health,info` is set: expose only what you need, never everything.

---

## 9. Testing

- `ShopApplicationTests` (`@SpringBootTest`): starts the whole application and checks it does not crash on startup. Slower, broad.
- `PingControllerTest` (`@WebMvcTest`): starts only the web layer and uses `MockMvc` to send fake requests through the real Spring MVC pipeline without opening a port. Fast, focused.

Rule of thumb for later: many fast focused tests, fewer broad ones. Read the `PingControllerTest` file; it is short.

---

## 10. Run it yourself

You need JDK 21 (`java -version` should say 21). On Windows use `mvnw.cmd` instead of `./mvnw`.

```
./mvnw verify                      # build + run tests
./mvnw spring-boot:run             # start the app
java -jar target/shop-0.0.1-SNAPSHOT.jar   # or run the built jar
```

In a second terminal:

```
curl http://localhost:8080/api/v1/ping
curl http://localhost:8080/actuator/health
```

(PowerShell: `Invoke-RestMethod http://localhost:8080/api/v1/ping`.)

Then explore: open `target/classes` (compiled output), `target/surefire-reports` (test results) and `target/shop-0.0.1-SNAPSHOT.jar` (it is a zip; open it and find your classes and the Tomcat libraries inside).

Things to break on purpose:
1. Change `spring.application.name` in `application.properties`, rebuild, call ping. Where did the new value appear?
2. Move `PingController` into a package outside `dev.elsepiol.shop`. What happens, and why (section 5)?
3. Remove `@RestController`. What does the endpoint return now?

---

## 11. Exercise 0: your first controller

**Task.** Add `GET /api/v1/greetings/{name}` that returns JSON like `{"message":"Hello, Anurag!"}`.

Rules the tests check:
- `name` must be 2 to 30 letters, `A-Z` or `a-z` only
- anything else must return HTTP 400

**Where.** Create `src/main/java/dev/elsepiol/shop/greeting/GreetingController.java`.

**Run the tests** (they fail at first, which is the point):

```
./mvnw test -Pexercises
```

Done when all 4 tests pass and `./mvnw verify` still passes.

**Read before you start** (search the page title if a link has moved):
- Spring MVC annotated controllers: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html
- Validation of controller method parameters: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html
- Spring Boot testing: https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html

**Questions to answer from the docs, in your own words:**
1. Where does the 400 come from when validation fails? Which exception, and who converts it to a response?
2. Why does the test class use `@WebMvcTest` and not `@SpringBootTest`?
3. What does a record give you compared with a normal class?

**Stretch (no tests, parallel components).** Make the same endpoint also accept `?name=` as a query parameter using `@RequestParam` with a default value. Then find out what `@RestControllerAdvice` and `ProblemDetail` do and change the 400 response to a structured JSON error body. You will need this in Level 1.

If you are stuck after a real attempt, the reference is `solutions/level-0/GreetingController.java.txt`. Compare it with yours and note every difference.

---

## 12. The scaling lens (Level 0)

- This is one process on one machine. Each request holds one Tomcat thread for as long as it runs. `ping` finishes in microseconds, so one instance handles a very large number of these per second.
- The limit appears when a handler waits on something slow (a database, another service). Threads pile up waiting, and the pool of 200 empties. Everything from Level 1 onward is about that waiting.
- The app keeps no state between requests ("stateless"). That single property is what lets you later run ten copies behind a load balancer. If it stored data in a field, ten copies would disagree.
- At large companies the same endpoint exists, but it is called per instance by the infrastructure, across thousands of instances, not by users.

---

## 13. Interview talk track

**One-minute explanation:** "It's a Spring Boot service built with Maven. The build produces a single executable jar with an embedded Tomcat. On startup Spring scans the package, creates beans and wires dependencies, and auto-configures the web layer from what's on the classpath. Requests go through the DispatcherServlet to controller methods, and Jackson serializes the responses. CI on GitHub Actions builds and tests every push with the same command I use locally."

**Questions you should now be able to answer:**
1. What does `@SpringBootApplication` do?
2. What is dependency injection, and why does Spring do it?
3. What is a starter, and how are versions chosen?
4. What happens between a request arriving and a JSON response leaving?
5. Difference between `@WebMvcTest` and `@SpringBootTest`?
6. Why is statelessness important for scaling?

---

## 14. What comes next

Level 1 adds a real database (PostgreSQL), the controller, service and repository layers, schema migrations, a proper error format and Docker. Same method: a feature, a lesson, then an exercise for you.
