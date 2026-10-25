package dev.elsepiol.shop.product;

/** The cursor was not made by us (edited, truncated, from another listing). HTTP 400. */
public class InvalidCursorException extends RuntimeException {

	public InvalidCursorException() {
		super("The cursor is not valid. Use the nextCursor value from a previous page unchanged");
	}

}
