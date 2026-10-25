package dev.elsepiol.shop.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.elsepiol.shop.support.Concurrently;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The token bucket with a fake clock: time only moves when the test says so. */
class RateLimiterTest {

	private static final long SECOND = 1_000_000_000L;

	private final AtomicLong now = new AtomicLong(42 * SECOND);

	private final RateLimiter limiter = new RateLimiter(now::get);

	private final Limit tenPerMinute = new Limit(10, Duration.ofMinutes(1)); // one token every 6 s

	private int allowedOf(int attempts, String key) {
		int allowed = 0;
		for (int i = 0; i < attempts; i++) {
			if (limiter.tryAcquire(key, tenPerMinute).allowed()) {
				allowed++;
			}
		}
		return allowed;
	}

	@Test
	void aFullBucketAllowsABurstUpToItsCapacityThenRefuses() {
		assertThat(allowedOf(15, "alice")).isEqualTo(10);
	}

	@Test
	void tokensComeBackAtTheSteadyRate() {
		allowedOf(10, "alice");

		now.addAndGet(5 * SECOND);
		assertThat(limiter.tryAcquire("alice", tenPerMinute).allowed()).as("5 s: no token yet").isFalse();
		now.addAndGet(1 * SECOND);
		assertThat(limiter.tryAcquire("alice", tenPerMinute).allowed()).as("6 s: one token").isTrue();
		assertThat(limiter.tryAcquire("alice", tenPerMinute).allowed()).as("and only one").isFalse();
	}

	@Test
	void aRefusalSaysHowLongToWait() {
		allowedOf(10, "alice");
		now.addAndGet(2 * SECOND);

		Decision refused = limiter.tryAcquire("alice", tenPerMinute);

		assertThat(refused.allowed()).isFalse();
		assertThat(refused.retryAfterSeconds()).isEqualTo(4); // the next token is due 6 s after the last
	}

	@Test
	void aLongPauseRefillsToCapacityButNotBeyond() {
		allowedOf(10, "alice");
		now.addAndGet(Duration.ofDays(3).toNanos());

		assertThat(allowedOf(15, "alice")).isEqualTo(10);
	}

	@Test
	void everyKeyHasItsOwnBucket() {
		allowedOf(10, "alice");

		assertThat(limiter.tryAcquire("alice", tenPerMinute).allowed()).isFalse();
		assertThat(limiter.tryAcquire("bob", tenPerMinute).allowed()).isTrue();
	}

	@Test
	void remainingCountsDown() {
		assertThat(limiter.tryAcquire("alice", tenPerMinute).remaining()).isEqualTo(9);
		assertThat(limiter.tryAcquire("alice", tenPerMinute).remaining()).isEqualTo(8);
	}

	/** The bucket is shared by every request thread of that client: never more than capacity get through. */
	@Test
	void fiftySimultaneousRequestsGetExactlyTheCapacity() throws Exception {
		List<Object> results = Concurrently.runAtOnce(50, i -> () -> limiter.tryAcquire("alice", tenPerMinute).allowed());

		assertThat(results.stream().filter(Boolean.TRUE::equals).count()).isEqualTo(10);
	}

}
