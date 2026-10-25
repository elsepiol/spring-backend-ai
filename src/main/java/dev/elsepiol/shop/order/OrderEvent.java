package dev.elsepiol.shop.order;

/**
 * The payload of the order events written to the outbox (as JSON). Keep events small and
 * factual: what happened and the ids needed to look the rest up.
 */
public record OrderEvent(long orderId, long userId, long totalCents) {

	public static final String PLACED = "OrderPlaced";
	public static final String CANCELLED = "OrderCancelled";

}
