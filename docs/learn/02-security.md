# Level 2: Security (authentication and authorization)

Goal: know exactly how the app decides who a caller is and what they may do, and be able to explain every piece without hand-waving. Security is where interviewers separate people who copied a tutorial from people who understand it.

Read Levels 0 and 1 first. Versions: Spring Boot 4.1.2 with Spring Security 7.1.2 (Nimbus JOSE for tokens).

Two words to keep apart for the whole level:
- **Authentication**: proving WHO you are ("this request comes from user 42").
- **Authorization**: deciding WHAT that user may do ("user 42 may not create products").

---

## 1. The picture

```
POST /api/v1/auth/register     create an account          (public)
POST /api/v1/auth/login        email + password  ->  JWT   (public)

GET  /api/v1/products          browse                      (public)
POST /api/v1/products          create   needs a JWT with role ADMIN
anything else                  needs a valid JWT           (deny by default)
```

After login the client keeps the token and sends it on every request as a header:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9....
```

The server keeps no memory of the login. Each request carries its own proof. That is "stateless authentication".

---

## 2. What is new in the repo

| Path | What it is |
|---|---|
| `db/migration/V2__create_users.sql` | The `users` table (migration number 2: Flyway applies it on top of V1) |
| `auth/User.java`, `Role.java`, `UserRepository.java` | The user entity, the two roles, data access |
| `auth/AuthService.java` | Register and login logic, password hashing |
| `auth/AuthController.java` | The two public endpoints |
| `auth/AdminBootstrap.java` | Creates the first admin from environment variables |
| `security/JwtConfig.java` | Builds the token encoder, decoder and password encoder |
| `security/JwtService.java` | Creates signed tokens |
| `security/SecurityConfig.java` | THE rules: who may call what |
| `security/SecurityProblemHandlers.java` | Writes 401 and 403 responses as problem-details JSON |
| `application.properties` | `app.security.*` settings (secret, lifetime, issuer) |
| `src/test/.../support/WebSliceSecurityConfig.java`, `TestUsers.java` | Test helpers: real rules in web tests, fake logged-in callers |

---

## 3. Passwords: never store them

`AuthService.register` stores `passwordEncoder.encode(password)`, never the password. The encoder is **bcrypt**:

- It is a one-way function: you can check a guess against the hash but cannot recover the password from it.
- It adds a random **salt** to each hash, so two users with the same password get different hashes, and precomputed "rainbow tables" are useless.
- It is deliberately **slow** (a tunable "work factor"; ours is the default of 10). Slow is the point: an attacker who steals the database can only test guesses slowly. Hashing with a fast function such as plain SHA-256 is a classic mistake.
- A bcrypt hash looks like `$2a$10$...`. The prefix records the algorithm and work factor, so you can raise the cost later and still verify old hashes.

Two details the code handles on purpose:
- **bcrypt only reads the first 72 bytes** of a password. `RegisterRequest` limits length to 72 characters, and `AuthService` also checks BYTES (a character like "é" is two bytes in UTF-8). Silently truncating would make two different long passwords equal.
- **Emails are lower-cased** before storing and looking up, and the `UNIQUE` constraint then makes them case-insensitively unique.

Rule to repeat in interviews: store a slow, salted hash; never log passwords; never email them.

---

## 4. Login without leaking information

Look at `AuthService.login`. Two behaviours are deliberate:

1. **Same error for "unknown email" and "wrong password"**: `Invalid email or password`. If the messages differed, an attacker could test which emails have accounts ("user enumeration").
2. **Same TIME for both.** If the email is unknown there is no hash to check, so a naive version returns instantly, and the faster response reveals the email does not exist. We therefore check the password against a throwaway `dummyHash` so both paths do the same slow work. `AuthServiceTest.loginForAnUnknownEmail...` verifies that the hash check still happens.

Registration has a similar leak ("email already exists" tells an attacker the email is registered). Many real products accept that for usability; high-security ones respond identically and email the owner instead. Know that it is a trade-off.

---

## 5. What a JWT is

A JSON Web Token has three base64url parts separated by dots: `header.claims.signature`.

```
header     {"alg":"HS256"}
claims     {"iss":"shop","sub":"42","email":"ada@example.com","role":"USER","iat":...,"exp":...}
signature  HMAC-SHA256(header + "." + claims, secret)
```

Facts that matter:
- **The claims are encoded, not encrypted.** Anyone holding the token can read them. Never put secrets in a JWT.
- **The signature proves integrity.** Change one character of the claims and the signature no longer matches; the server rejects it. Only someone with the secret can produce a valid one. `JwtServiceTest.aTamperedTokenIsRejected` proves this.
- **Standard claims:** `iss` (who issued it), `sub` (who it is about; we use the user id), `iat` (issued at), `exp` (expiry). `email` and `role` are our own.
- **The server verifies without a database call**: just the secret and some arithmetic. That is why JWTs scale well (section 12).

**HS256 vs RS256.** We use HS256: one shared secret signs and verifies. Fine while one service does both. When many services must verify tokens, you do not want to hand each of them the signing secret, so you use an asymmetric key pair (RS256 or ES256): the issuer signs with a private key, everyone verifies with a public key. Real systems also add a key id (`kid`) header so keys can be rotated.

`JwtConfig` refuses to start if the secret is under 32 bytes (HS256 wants a 256-bit key) and logs a warning if the built-in development secret is in use. The secret comes from `JWT_SECRET`; the default in `application.properties` is public because the repo is public, so it is for local use only.

---

## 6. Validation: what the decoder checks

`JwtConfig.jwtDecoder` checks on every request: signature, expiry (`exp`), not-before, and `iss` equals `shop`. Tests prove each: tampered, wrong secret, expired, wrong issuer.

One subtlety that comes up in interviews: **clock skew**. The decoder tolerates 60 seconds of difference between clocks, because servers' clocks are never perfectly in sync. So a token that expired 10 seconds ago is still accepted. `JwtServiceTest.anExpiredTokenIsRejected` therefore uses a token that expired nearly two hours ago.

---

## 7. How a request is checked

Security runs as a chain of servlet **filters BEFORE Spring MVC** and your controller ever see the request:

```
request
  -> BearerTokenAuthenticationFilter   finds "Authorization: Bearer ...", calls the JwtDecoder,
                                       on success stores an Authentication (user + authorities)
  -> AuthorizationFilter               checks the rules in SecurityConfig against that Authentication
  -> DispatcherServlet -> your controller
```

- No token, or a bad one: the entry point answers **401 Unauthorized**.
- Valid token but the rules forbid the action: the access-denied handler answers **403 Forbidden**.
- 401 means "I do not know who you are". 403 means "I know who you are, and no". Mixing them up is a common mistake.

Because this happens in a filter, `ApiExceptionHandler` (a Spring MVC component) never sees these failures. `SecurityProblemHandlers` writes the same problem-details JSON by hand so 401 and 403 look like every other error. The 401 also carries the standard `WWW-Authenticate: Bearer` header.

---

## 8. SecurityConfig, line by line

Open `SecurityConfig.java`. The rules are an ordered list; **the first match wins**.

```
permitAll   /error, /actuator/health, /actuator/info, /api/v1/ping, /api/v1/greetings/**
permitAll   POST /api/v1/auth/register, POST /api/v1/auth/login
permitAll   GET  /api/v1/products and /api/v1/products/**
ADMIN only  everything else under /api/v1/products/**      (POST, PUT, DELETE)
authenticated  anyRequest()                                 (the catch-all)
```

- **Order matters.** If you moved the ADMIN rule above the GET rule, anonymous browsing would break, because the ADMIN rule would match first. (A good "break it on purpose".)
- **Deny by default.** The last line means a new endpoint you forget to list is protected, not open. `SecurityRulesTest.unlistedPathsAreDeniedByDefault` guards that. The opposite design (allow everything unless listed) is how accidental data leaks happen.
- **Stateless sessions:** `SessionCreationPolicy.STATELESS`, so the server never creates a login session.
- **CSRF disabled.** CSRF attacks work because browsers attach cookies automatically to requests. We use a bearer token that client code attaches explicitly, so there is no automatic credential to abuse. If you ever switch to storing the token in a cookie, you must turn CSRF protection back on.
- **Roles:** the token's `role` claim (`ADMIN`) becomes the authority `ROLE_ADMIN`, which `hasRole("ADMIN")` checks. The converter at the bottom of the file does that mapping.

---

## 9. Where does the first admin come from?

Registration only creates `USER`s (never trust the client to choose its own role). `AdminBootstrap` creates an admin at startup only if `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` are set and the email is not already registered. Unset in production means no default admin exists, which is the safe default. Compare with the dangerous alternative: a hard-coded `admin/admin` account.

---

## 10. Testing security

| Test | Needs Docker | What it proves |
|---|---|---|
| `JwtServiceTest` | no | claims, tamper, wrong secret, expiry, issuer, short secret refused |
| `AuthServiceTest` | no | hashing, lower-casing, duplicates, the 72-byte rule, same-error and timing behaviour |
| `SecurityRulesTest` | no | 401 vs 403, problem-details bodies, ADMIN-only writes, deny by default |
| `AuthApiTest` | yes | the whole flow with REAL tokens: register, login, protected calls, tampering |

Two helpers make web tests honest:
- `WebSliceSecurityConfig` imports our REAL `SecurityConfig` into `@WebMvcTest` slices (slices skip your `@Configuration` classes), and swaps the token decoder for one that accepts nothing.
- `TestUsers.admin()` / `user()` mark a MockMvc request as "authenticated as someone with this role" without making tokens. Use it when security is not what you are testing.

---

## 11. Run it yourself

Start the database, then the app with a bootstrap admin (Windows PowerShell: `$env:NAME="value"` instead of the prefix form):

```
docker compose up -d db
BOOTSTRAP_ADMIN_EMAIL=admin@example.com BOOTSTRAP_ADMIN_PASSWORD=local-admin-password ./mvnw spring-boot:run
```

In a second terminal (bash; `$TOKEN` is a shell variable):

```
# 1. register a normal user
curl -i -X POST localhost:8080/api/v1/auth/register -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","password":"correct horse battery"}'

# 2. log in and keep the token
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","password":"correct horse battery"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

# 3. read the claims yourself (the middle part is just base64)
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null; echo

# 4. try to create a product as a normal user  -> 403
curl -i -X POST localhost:8080/api/v1/products -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"sku":"MUG-1","name":"Mug","priceCents":100}'

# 5. no token -> 401 with WWW-Authenticate; browsing needs none -> 200
curl -i -X POST localhost:8080/api/v1/products -H "Content-Type: application/json" -d '{"sku":"X","name":"X","priceCents":1}'
curl -i localhost:8080/api/v1/products
```

Now log in as `admin@example.com` / `local-admin-password`, repeat step 4 with the admin token, and you should get 201.

**Break it on purpose:**
1. Change one character in the middle of a token and send it. Which status, and which filter said no?
2. Restart the app with a different `JWT_SECRET` and reuse an old token. Why is it rejected, and what does that tell you about secret rotation?
3. Move the `hasRole("ADMIN")` rule above the `GET` rule in `SecurityConfig`. What breaks for anonymous users?
4. Delete `.anyRequest().authenticated()`. Which endpoints (in this app) change behaviour, and why is that dangerous?
5. Set `app.security.jwt.secret=short` and start the app. Read the error.

---

## 12. Exercise 2: who am I, and change my password

Needs Docker running. Two additions:

**Part A: `GET /api/v1/me`.** Returns `{"id":42,"email":"...","role":"USER"}` for the caller, read from the validated token, with no database query. Without a token it is 401 (you will not need to write that part).

**Part B: `POST /api/v1/auth/change-password`** with body `{"currentPassword":"...","newPassword":"..."}`.
- success: `204 No Content`, the old password stops working and the new one works
- wrong current password: `400` (reuse `InvalidPasswordException`)
- new password must follow the same rules as registration (10 to 72, same byte check)
- no token: 401

Files you will touch: a new `MeController` and `ChangePasswordRequest`, plus `AuthController`, `AuthService` and `User`.

```
./mvnw test -Pexercises
```

Done when the Level 2 exercise tests pass and `./mvnw verify` still passes.

Surprise: two of the six tests pass before you write anything (the two "without a token is 401" ones). Work out why. It is the deny-by-default rule from section 8 doing its job.

**Read first** (search the page title if a link has moved):
- Spring Security, JWT resource server: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- Spring Security, authorize HTTP requests: https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html
- Spring Security, password storage: https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html
- RFC 7519, JSON Web Token: https://datatracker.ietf.org/doc/html/rfc7519

**Answer in your own words:**
1. What does `@AuthenticationPrincipal Jwt jwt` give you, and which filter put it there?
2. In Part B you change a field on a loaded `User` and never call `save`. Why does the change still reach the database? (Search: JPA dirty checking, and what `@Transactional` has to do with it.)
3. After the password change, does the user's OLD token still work? Why? Name two ways to fix that.

**Stretch (parallel components, no tests):**
- Add `GET /api/v1/admin/users` (list users) protected with method security instead of URL rules: `@EnableMethodSecurity` and `@PreAuthorize("hasRole('ADMIN')")`. Then argue when you would prefer each style.
- Design, on paper, a refresh-token flow: short-lived access token (15 minutes) plus a long-lived refresh token stored (hashed) in the database so it CAN be revoked. Which table, which endpoints, what happens on logout?

Reference solution: the files under `solutions/level-2/src/main/java/dev/elsepiol/shop/auth/`. Compare after you try.

---

## 13. Honest limitations of what we built

An interviewer who knows security will probe these, so know them first:

- **Tokens cannot be revoked before they expire.** A stolen token works until `exp`; changing a password does not kill existing tokens. Mitigations: short lifetimes (we use 15 minutes), a refresh-token flow, or a server-side deny-list. Each one gives back a little of the statelessness.
- **No login throttling.** Nothing stops 1,000 password guesses per second against one account. Level 5 adds rate limiting.
- **One shared secret (HS256).** Fine for one service. Not for many (section 5).
- **No email verification, no password reset, no MFA.** Real products have all three.
- **Where the browser stores the token** (frontend level): in JavaScript-readable storage it can be stolen by any cross-site-scripting bug; in an `HttpOnly` cookie it cannot be read by scripts but then CSRF protection matters again. There is no free lunch, only trade-offs.
- **Build or buy.** Most companies do not build this. They use an identity provider (Keycloak, Auth0, AWS Cognito, Google Identity) speaking OAuth 2.0 / OpenID Connect, and the app only validates tokens, which is exactly the "resource server" half we already have.

---

## 14. The scaling lens (Level 2)

- **Verifying a token is cheap.** HMAC plus claim checks takes microseconds and needs no database. Compare with server-side sessions, where every request hits a session store. This is why 10,000 requests per second of authenticated traffic is no problem for token verification, and why any instance can serve any request (statelessness again).
- **Logging in is expensive, on purpose.** Each login does one bcrypt hash, which costs on the order of tens to a couple of hundred milliseconds of one CPU core (measure it on your machine rather than trusting this range). So a single core can only do roughly ten logins per second. A login storm (a marketing email, a game launch, or an attack) saturates CPUs long before the database. Defences: rate limit login, scale instances horizontally, keep access tokens short and refreshing cheap so people log in rarely.
- **Secrets across many instances.** Every instance must have the same `JWT_SECRET`, delivered by a secrets manager and never stored in git. Rotating it invalidates all tokens unless you support multiple keys (hence `kid` and asymmetric keys).
- **At Google or Meta scale** authentication is a separate platform: central identity service, short-lived credentials, service-to-service identity (mutual TLS) so internal calls are authenticated too, and anomaly detection on logins. The application code mostly just validates a token and reads claims, like our `/me` endpoint.

---

## 15. Interview talk track

"Passwords are stored as salted bcrypt hashes, and login returns the same error and takes the same time whether the email exists or not. Login issues a short-lived signed JWT with the user id, email and role. Every request goes through Spring Security's filter chain: a bearer-token filter validates signature, expiry and issuer, then the authorization rules run, with a deny-by-default catch-all and ADMIN-only writes on the catalog. 401 means unauthenticated and 403 means authenticated but not allowed, both returned as problem-details JSON. The app is stateless, so any instance can serve any request, and CSRF is off because tokens are sent explicitly rather than in cookies. Known limitations are no revocation before expiry, no login throttling yet, and a shared HS256 secret; in production I would use an identity provider or asymmetric keys."

**Questions you should now be able to answer:**
1. Authentication vs authorization? 401 vs 403?
2. Why bcrypt and not SHA-256? What is a salt?
3. What is in a JWT, who can read it, and what stops someone editing it?
4. HS256 vs RS256: when does each fit?
5. Why is CSRF protection disabled here, and when must it be on?
6. What does "deny by default" mean and where is it in the code?
7. How would you revoke a token? What does each approach cost?
8. What limits login throughput, and how would you protect it?
