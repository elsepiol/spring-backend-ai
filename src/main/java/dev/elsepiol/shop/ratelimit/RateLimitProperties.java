package dev.elsepiol.shop.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of app.rate-limit.* in application.properties.
 *
 * @param auth register and login, counted per client address
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(Limit auth) {
}
