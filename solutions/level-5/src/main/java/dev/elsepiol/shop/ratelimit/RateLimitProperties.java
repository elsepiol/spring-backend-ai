package dev.elsepiol.shop.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of app.rate-limit.* in application.properties.
 *
 * @param auth register and login, counted per client address
 * @param orders placing orders, counted per logged-in user (SOLUTION, Exercise 5A)
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(Limit auth, Limit orders) {
}
