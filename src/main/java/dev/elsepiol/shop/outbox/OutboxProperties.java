package dev.elsepiol.shop.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of app.outbox.* in application.properties.
 *
 * @param batchSize how many events one worker claims at a time
 * @param lease how long a claimed event is reserved for that worker before others may take it
 * @param maxAttempts after this many failed deliveries the event is DEAD (a "dead letter")
 * @param retryBaseDelay first retry waits this long, then 2x, 4x, 8x... (exponential backoff)
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(int batchSize, Duration lease, int maxAttempts, Duration retryBaseDelay) {
}
