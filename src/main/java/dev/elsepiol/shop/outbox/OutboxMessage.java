package dev.elsepiol.shop.outbox;

/** One claimed event, as a handler sees it. attempts counts this delivery too (1 = first try). */
public record OutboxMessage(long id, String type, long aggregateId, String payload, int attempts) {
}
