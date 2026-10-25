package dev.elsepiol.shop.ratelimit;

/**
 * The answer for one request: allowed (and how many requests are left right now), or denied (and
 * how long until the next one would be allowed, for the Retry-After header).
 */
public record Decision(boolean allowed, long remaining, long retryAfterNanos) {

	static Decision allowed(long remaining) {
		return new Decision(true, remaining, 0);
	}

	static Decision denied(long retryAfterNanos) {
		return new Decision(false, 0, retryAfterNanos);
	}

	/** Whole seconds, rounded up, at least 1: what Retry-After needs. */
	public long retryAfterSeconds() {
		return Math.max(1, (retryAfterNanos + 999_999_999L) / 1_000_000_000L);
	}

}
