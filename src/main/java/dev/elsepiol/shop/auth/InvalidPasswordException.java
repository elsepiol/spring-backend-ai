package dev.elsepiol.shop.auth;

/** A password we refuse to accept. Mapped to HTTP 400 by ApiExceptionHandler. */
public class InvalidPasswordException extends RuntimeException {

	public InvalidPasswordException(String message) {
		super(message);
	}

}
