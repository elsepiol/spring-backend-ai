package dev.elsepiol.shop.order;

/** The same Idempotency-Key was sent with a DIFFERENT request. HTTP 422: that is a client bug. */
public class IdempotencyKeyReuseException extends RuntimeException {

	public IdempotencyKeyReuseException() {
		super("This Idempotency-Key was already used with a different request");
	}

}
