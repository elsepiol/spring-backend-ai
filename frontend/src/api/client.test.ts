import { describe, expect, it, vi } from 'vitest';
import { fakeApi, json, problem } from '../test/fakeApi';
import { ApiError, createApiClient, NetworkError } from './client';

function client(fetch: typeof globalThis.fetch, token: string | null = null, onUnauthorized = vi.fn()) {
  return { api: createApiClient({ fetch, getToken: () => token, onUnauthorized }), onUnauthorized };
}

describe('the API client', () => {
  it('sends JSON and the bearer token to /api/v1', async () => {
    const server = fakeApi().on('POST /orders', json({ id: 1 }, 201));
    const { api } = client(server.fetch, 'the.jwt');

    await api.request('POST', '/orders', { body: { items: [] } });

    const [call] = server.calls;
    expect(call.headers.get('Authorization')).toBe('Bearer the.jwt');
    expect(call.headers.get('Content-Type')).toBe('application/json');
    expect(call.body).toEqual({ items: [] });
    expect(vi.mocked(server.fetch).mock.calls[0][0]).toBe('/api/v1/orders');
  });

  it('turns a problem-details body into an ApiError with field errors and the trace id', async () => {
    const server = fakeApi().on('POST /auth/register',
      problem(400, 'Invalid request content.', { errors: { email: 'must be a well-formed email address' }, traceId: 'abc123' }));
    const { api } = client(server.fetch);

    const error = await api.request('POST', '/auth/register', { body: {} }).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 400, detail: 'Invalid request content.', traceId: 'abc123',
      fieldErrors: { email: 'must be a well-formed email address' } });
  });

  it('takes the trace id from the X-Trace-Id header when the body has none', async () => {
    const server = fakeApi().on('GET /products/1', problem(404, 'Product 1 not found', {}, { 'X-Trace-Id': 'from-header' }));
    const { api } = client(server.fetch);

    await expect(api.request('GET', '/products/1')).rejects.toMatchObject({ traceId: 'from-header' });
  });

  it('reads Retry-After from a 429', async () => {
    const server = fakeApi().on('POST /auth/login', problem(429, 'Too many requests', {}, { 'Retry-After': '20' }));
    const { api } = client(server.fetch);

    await expect(api.request('POST', '/auth/login', { body: {} })).rejects.toMatchObject({ status: 429, retryAfterSeconds: 20 });
  });

  it('reports a 401 to a request that carried a token: the session is over', async () => {
    const server = fakeApi().on('GET /orders', problem(401, 'Authentication is required'));
    const { api, onUnauthorized } = client(server.fetch, 'expired.jwt');

    await expect(api.request('GET', '/orders')).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('does not treat a failed login (401 without a token) as an ended session', async () => {
    const server = fakeApi().on('POST /auth/login', problem(401, 'Invalid email or password'));
    const { api, onUnauthorized } = client(server.fetch, null);

    await expect(api.request('POST', '/auth/login', { body: {} })).rejects.toMatchObject({ status: 401 });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('reports "no answer at all" as a NetworkError, not as a server error', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const { api } = client(fetch);

    await expect(api.request('GET', '/products/1')).rejects.toBeInstanceOf(NetworkError);
  });

  it('gives up after the timeout', async () => {
    // A server that never answers, but stops waiting when the request is aborted, as fetch does.
    const fetch = vi.fn((_url: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const api = createApiClient({ fetch, getToken: () => null, timeoutMs: 20 });

    await expect(api.request('GET', '/products/1')).rejects.toThrow('The server took too long to answer.');
  });

  it('returns no data for 204 No Content', async () => {
    const server = fakeApi().on('PUT /products/1/stock', new Response(null, { status: 204 }));
    const { api } = client(server.fetch, 'admin.jwt');

    await expect(api.request('PUT', '/products/1/stock', { body: { quantity: 1 } })).resolves.toMatchObject({ status: 204, data: undefined });
  });
});
