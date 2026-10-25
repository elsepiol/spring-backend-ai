import { vi } from 'vitest';

/**
 * A stand-in for the Spring Boot API: register what each "METHOD /path" answers, then pass
 * fakeApi.fetch to <ShopProvider>. Every call is recorded so tests can check what was SENT
 * (headers, bodies). A request nobody registered fails the test loudly.
 */
export interface RecordedCall {
  method: string;
  path: string;
  headers: Headers;
  body: unknown;
}

type Answer = Response | ((call: RecordedCall) => Response | Promise<Response>);

export function fakeApi() {
  const routes = new Map<string, Answer[]>();
  const calls: RecordedCall[] = [];

  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost');
    const call: RecordedCall = {
      method: init?.method ?? 'GET',
      path: url.pathname.replace(/^\/api\/v1/, '') + url.search,
      headers: new Headers(init?.headers),
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    };
    calls.push(call);
    const key = `${call.method} ${url.pathname.replace(/^\/api\/v1/, '')}`;
    const answers = routes.get(key);
    if (!answers || answers.length === 0) {
      throw new Error(`Unexpected request: ${key}`);
    }
    // Answers are used in order; the last one repeats.
    const answer = answers.length > 1 ? answers.shift()! : answers[0];
    return typeof answer === 'function' ? answer(call) : answer.clone();
  }) as unknown as typeof globalThis.fetch;

  return {
    fetch,
    calls,
    /** Answer "METHOD /path" (path without /api/v1 and without query) with these, in order. */
    on(route: string, ...answers: Answer[]) {
      routes.set(route, answers);
      return this;
    },
    callsTo(route: string) {
      const [method, path] = route.split(' ');
      return calls.filter((c) => c.method === method && c.path.split('?')[0] === path);
    },
  };
}

export function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

/** A problem-details error body, like ApiExceptionHandler writes. */
export function problem(status: number, detail: string, extra: Record<string, unknown> = {},
  headers: Record<string, string> = {}) {
  return new Response(JSON.stringify({ type: 'about:blank', title: 'Error', status, detail, ...extra }),
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } });
}

/** A JWT-shaped token with these claims (unsigned: the frontend never verifies signatures). */
export function fakeToken(claims: Partial<{ sub: string; email: string; role: string; exp: number }> = {}) {
  const payload = { sub: '42', email: 'ada@example.com', role: 'USER', exp: Math.floor(Date.now() / 1000) + 900, ...claims };
  const encode = (value: unknown) => btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'HS256' })}.${encode(payload)}.signature`;
}

export const mug = { id: 7, sku: 'MUG-001', name: 'Blue Mug', description: 'A mug', priceCents: 1250, createdAt: '2026-01-01T00:00:00Z' };
export const plate = { id: 8, sku: 'PLATE-1', name: 'Plate', description: null, priceCents: 900, createdAt: '2026-01-01T00:00:00Z' };
