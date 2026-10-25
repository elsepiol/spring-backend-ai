/**
 * The one place the frontend talks HTTP. Every request goes through request(), which
 *  - adds the bearer token (Level 2) if the user is logged in,
 *  - gives up after a timeout instead of spinning forever,
 *  - turns problem-details error bodies (RFC 9457, Level 1) into an ApiError with the fields
 *    the UI needs: a message, per-field validation errors, Retry-After, and the trace id that
 *    support can look up (Level 6),
 *  - tells the app when the server says 401 to a request that carried a token: the session is
 *    over (expired or revoked) and the user must log in again.
 */

/** The server answered, with an error status. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly title: string,
    readonly detail: string,
    /** Quote this to support: it finds every log line of this request (X-Trace-Id). */
    readonly traceId: string | null,
    /** From a 400 validation error: field name -> message. */
    readonly fieldErrors: Record<string, string>,
    /** From 429 or 503: whole seconds to wait before trying again. */
    readonly retryAfterSeconds: number | null,
    /** The whole problem-details body, for extra properties such as productId. */
    readonly body: Record<string, unknown>,
  ) {
    super(detail);
    this.name = 'ApiError';
  }
}

/** No answer at all: offline, DNS, connection refused, or our own timeout. The request MAY have run. */
export class NetworkError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'NetworkError';
  }
}

export interface ApiResponse<T> {
  data: T;
  status: number;
  headers: Headers;
}

export interface RequestOptions {
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export interface ApiClient {
  request<T>(method: string, path: string, options?: RequestOptions): Promise<ApiResponse<T>>;
}

export interface ApiClientOptions {
  /** '/api/v1': same origin as the page (Vite's proxy in development, nginx in production). */
  baseUrl?: string;
  getToken: () => string | null;
  onUnauthorized?: () => void;
  fetch?: typeof fetch;
  timeoutMs?: number;
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const baseUrl = options.baseUrl ?? '/api/v1';
  const doFetch = options.fetch ?? ((input, init) => fetch(input, init));
  const timeoutMs = options.timeoutMs ?? 10_000;

  return {
    async request<T>(method: string, path: string, request: RequestOptions = {}): Promise<ApiResponse<T>> {
      const headers = new Headers({ Accept: 'application/json, application/problem+json', ...request.headers });
      const token = options.getToken();
      if (token) {
        headers.set('Authorization', `Bearer ${token}`);
      }
      let body: string | undefined;
      if (request.body !== undefined) {
        headers.set('Content-Type', 'application/json');
        body = JSON.stringify(request.body);
      }

      const controller = new AbortController();
      let timedOut = false;
      const timer = setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, timeoutMs);
      request.signal?.addEventListener('abort', () => controller.abort());

      let response: Response;
      try {
        response = await doFetch(baseUrl + path, { method, headers, body, signal: controller.signal });
      } catch (cause) {
        if (request.signal?.aborted) {
          throw cause; // the caller cancelled (e.g. the page was left): not an error to show
        }
        throw new NetworkError(timedOut ? 'The server took too long to answer.' : 'Could not reach the server.',
          { cause });
      } finally {
        clearTimeout(timer);
      }

      if (response.ok) {
        const data = response.status === 204 ? undefined : await response.json();
        return { data: data as T, status: response.status, headers: response.headers };
      }
      if (response.status === 401 && token) {
        options.onUnauthorized?.();
      }
      throw await toApiError(response);
    },
  };
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: Record<string, unknown> = {};
  if ((response.headers.get('Content-Type') ?? '').includes('json')) {
    try {
      problem = await response.json();
    } catch {
      // not JSON after all: keep the generic message below
    }
  }
  const retryAfter = response.headers.get('Retry-After');
  return new ApiError(
    response.status,
    typeof problem.title === 'string' ? problem.title : response.statusText,
    typeof problem.detail === 'string' ? problem.detail : `The request failed (HTTP ${response.status}).`,
    typeof problem.traceId === 'string' ? problem.traceId : response.headers.get('X-Trace-Id'),
    isStringMap(problem.errors) ? problem.errors : {},
    retryAfter !== null && /^\d+$/.test(retryAfter) ? Number(retryAfter) : null,
    problem,
  );
}

function isStringMap(value: unknown): value is Record<string, string> {
  return typeof value === 'object' && value !== null && Object.values(value).every((v) => typeof v === 'string');
}
