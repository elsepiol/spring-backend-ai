import { ApiError, NetworkError } from '../api/client';

/** What to tell a person about a failure. Never a stack trace, never "undefined". */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 429) {
      return error.retryAfterSeconds !== null
        ? `Too many requests. Please wait ${error.retryAfterSeconds} seconds and try again.`
        : 'Too many requests. Please wait a moment and try again.';
    }
    if (error.status === 503) {
      return 'The shop is busy right now. Please try again in a moment.';
    }
    if (error.status >= 500) {
      return 'Something went wrong on our side. Please try again.';
    }
    return error.detail;
  }
  if (error instanceof NetworkError) {
    return `${error.message} Check your connection and try again.`;
  }
  return 'Something unexpected went wrong.';
}

/**
 * role="alert" makes screen readers announce the message. The trace id lets support find this
 * exact request in the logs (Level 6), so we show it, small, under any server error.
 */
export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) {
    return null;
  }
  const traceId = error instanceof ApiError ? error.traceId : null;
  return (
    <div role="alert" className="error">
      <p>{describeError(error)}</p>
      {traceId && <p className="trace">Reference for support: <code>{traceId}</code></p>}
    </div>
  );
}
