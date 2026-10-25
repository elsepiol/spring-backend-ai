package dev.elsepiol.shop.auth;

/**
 * Mapped to HTTP 401. The message is deliberately the same whether the email is
 * unknown or the password is wrong, so an attacker cannot learn which emails exist.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Invalid email or password");
	}

}
