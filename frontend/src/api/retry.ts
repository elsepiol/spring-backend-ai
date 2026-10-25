/**
 * EXERCISE (frontend, Part B): see docs/learn/07-frontend.md, section "Exercise".
 *
 * Should run task(), and when it fails in a way that is worth retrying, wait and run it again,
 * at most `attempts` times in total. Right now it simply runs it once.
 */
export interface RetryOptions {
  attempts?: number;
  baseDelayMs?: number;
  /** Injected so tests can control time. */
  sleep?: (ms: number) => Promise<void>;
}

export async function retrying<T>(task: () => Promise<T>, _options: RetryOptions = {}): Promise<T> {
  return task();
}
