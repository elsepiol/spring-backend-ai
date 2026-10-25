package dev.elsepiol.shop.order;

/** The order is not in a state that can be cancelled (already cancelled). HTTP 409. */
public class OrderNotCancellableException extends RuntimeException {

	public OrderNotCancellableException(long id) {
		super("Order " + id + " cannot be cancelled");
	}

}
