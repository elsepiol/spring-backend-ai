package dev.elsepiol.shop.ratelimit;

import java.time.Duration;

/**
 * "capacity requests per period", e.g. 10 per minute. A client may use all 10 at once (a burst),
 * after which it gets one more every period / capacity (here one every 6 seconds).
 */
public record Limit(int capacity, Duration period) {

	public Limit {
		if (capacity < 1 || period == null || period.isZero() || period.isNegative()) {
			throw new IllegalArgumentException("A limit needs a capacity >= 1 and a positive period");
		}
	}

	long nanosPerToken() {
		return Math.max(1, period.toNanos() / capacity);
	}

}
