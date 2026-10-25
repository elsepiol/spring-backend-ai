package dev.elsepiol.shop.ratelimit;

import java.util.List;

/** The limiter plus the rules SecurityConfig's RateLimitFilter applies, in order. */
public record RateLimitRules(RateLimiter limiter, List<RateLimitRule> rules) {

	/** No limits at all (web slice tests). */
	public static RateLimitRules none() {
		return new RateLimitRules(new RateLimiter(), List.of());
	}

}
