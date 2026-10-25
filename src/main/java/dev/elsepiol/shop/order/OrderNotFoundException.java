package dev.elsepiol.shop.order;

/**
 * Used both for "no such order" and "not your order". Answering 403 for someone else's
 * order would confirm that the id exists; 404 reveals nothing.
 */
public class OrderNotFoundException extends RuntimeException {

	public OrderNotFoundException(long id) {
		super("Order " + id + " not found");
	}

}
