package dev.elsepiol.shop.ratelimit;

/**
 * The token bucket algorithm. A bucket holds up to capacity tokens and starts full. Each request
 * takes one; no token, no request. Tokens drip back in at a steady rate (one every
 * nanosPerToken), but never above capacity.
 *
 * This allows short bursts (a page that fires 5 calls at once) while capping the long-run rate,
 * which a plain "N per fixed minute" counter does badly: with fixed windows a client can send N
 * at 12:00:59 and N more at 12:01:00, twice the limit in two seconds.
 *
 * Time is passed in (System.nanoTime() in production) so tests can control it. Nothing is
 * scheduled: the refill is computed from the elapsed time whenever the bucket is used.
 */
final class TokenBucket {

	private final long capacity;
	private final long nanosPerToken;
	private long tokens;
	private long lastRefill;

	TokenBucket(Limit limit, long now) {
		this.capacity = limit.capacity();
		this.nanosPerToken = limit.nanosPerToken();
		this.tokens = capacity;
		this.lastRefill = now;
	}

	/**
	 * Takes a token if there is one. synchronized: two requests from the same client at the same
	 * instant must not both take the last token (the check-then-act race from Level 3, in memory).
	 */
	synchronized Decision tryTake(long now) {
		long earned = (now - lastRefill) / nanosPerToken;
		if (earned >= capacity - tokens) {
			tokens = capacity; // full; time spent full earns nothing
			lastRefill = now;
		}
		else if (earned > 0) {
			tokens += earned;
			lastRefill += earned * nanosPerToken; // keep the fraction of the next token already earned
		}
		if (tokens > 0) {
			tokens--;
			return Decision.allowed(tokens);
		}
		return Decision.denied(nanosPerToken - (now - lastRefill));
	}

}
