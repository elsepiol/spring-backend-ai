package dev.elsepiol.shop.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the app.security.jwt.* settings in application.properties. */
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(String secret, Duration ttl, String issuer) {
}
