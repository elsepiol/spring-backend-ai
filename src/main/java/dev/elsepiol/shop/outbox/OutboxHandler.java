package dev.elsepiol.shop.outbox;

/**
 * Does the work for one event type. Any Spring bean implementing this is found by OutboxWorker.
 *
 * Delivery is AT LEAST ONCE: after a crash, a timeout or a lost "done" update, the same event is
 * handled again. Every handler must therefore be idempotent (handling twice = handling once).
 * Throwing means "failed, retry later".
 */
public interface OutboxHandler {

	/** The event type this handler takes, e.g. "OrderPlaced". */
	String type();

	void handle(OutboxMessage message) throws Exception;

}
