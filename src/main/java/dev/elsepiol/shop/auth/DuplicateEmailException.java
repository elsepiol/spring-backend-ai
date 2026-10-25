package dev.elsepiol.shop.auth;

/** Mapped to HTTP 409 by ApiExceptionHandler. */
public class DuplicateEmailException extends RuntimeException {

	public DuplicateEmailException() {
		super("An account with this email already exists");
	}

}
