import { ApiError, NetworkError } from './client';

/**
 * SOLUTION (frontend exercise, Part B). Runs task(); when it fails in a way that is worth
 * retrying, waits and runs it again, at most `attempts` times in total.
 *
 * Worth retrying:
 *  - NetworkError: no answer at all. The request may or may not have reached the server, so this
 *    is ONLY safe for idempotent requests (GET, or a POST carrying an Idempotency-Key).
 *  - 502/503/504: the server or a proxy in front of it is overloaded or restarting (Level 4's 503).
 * Not worth retrying: every other error. 400/409/422 would fail the same way again; 401 needs a
 * login; 429 means "slow down", and an automatic retry is the opposite (Part A shows the wait).
 */
export interface RetryOptions {
  attempts?: number;
  baseDelayMs?: number;
  /** Injected so tests can control time. */
  sleep?: (ms: number) => Promise<void>;
}

const RETRYABLE_STATUSES = [502, 503, 504];

export async function retrying<T>(task: () => Promise<T>, options: RetryOptions = {}): Promise<T> {
  const attempts = options.attempts ?? 3;
  const baseDelayMs = options.baseDelayMs ?? 250;
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));

  for (let attempt = 1; ; attempt++) {
    try {
      return await task();
    } catch (error) {
      if (attempt >= attempts || !isRetryable(error)) {
        throw error;
      }
      await sleep(delayBefore(attempt + 1, error, baseDelayMs));
    }
  }
}

function isRetryable(error: unknown): boolean {
  return error instanceof NetworkError || (error instanceof ApiError && RETRYABLE_STATUSES.includes(error.status));
}

/** The server knows best (Retry-After); otherwise double the wait each time: 1x, 2x, 4x... */
function delayBefore(nextAttempt: number, error: unknown, baseDelayMs: number): number {
  if (error instanceof ApiError && error.retryAfterSeconds !== null) {
    return error.retryAfterSeconds * 1000;
  }
  return baseDelayMs * 2 ** (nextAttempt - 2);
}
