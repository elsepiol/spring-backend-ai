package dev.elsepiol.shop.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * One token bucket per key ("auth:203.0.113.7", "orders:user-42"), kept in memory.
 *
 * The buckets live in a bounded Caffeine cache, not a plain map: the keys come from clients, and
 * an attacker with many addresses could otherwise fill the heap. A bucket that has not been used
 * for an hour is dropped; it would be full again by then anyway.
 *
 * IN MEMORY MEANS PER INSTANCE. With 4 app instances behind a load balancer, a client can get
 * up to 4x the limit. For an exact global limit the buckets must live in one shared place (Redis
 * with an atomic script, or the API gateway in front of all instances). See the Level 5 lesson.
 */
public class RateLimiter {

	private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
			.maximumSize(100_000)
			.expireAfterAccess(Duration.ofHours(1))
			.build();

	private final LongSupplier clock;

	public RateLimiter() {
		this(System::nanoTime);
	}

	/** clock: nanoseconds from any fixed origin. Tests pass a fake one. */
	public RateLimiter(LongSupplier clock) {
		this.clock = clock;
	}

	public Decision tryAcquire(String key, Limit limit) {
		long now = clock.getAsLong();
		return buckets.get(key, k -> new TokenBucket(limit, now)).tryTake(now);
	}

}
