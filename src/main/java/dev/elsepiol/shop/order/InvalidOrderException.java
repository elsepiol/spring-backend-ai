package dev.elsepiol.shop.order;

/** The order itself is unacceptable (unknown product, missing key, absurd total). HTTP 400. */
public class InvalidOrderException extends RuntimeException {

	public InvalidOrderException(String message) {
		super(message);
	}

}
