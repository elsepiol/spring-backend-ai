package dev.elsepiol.shop.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The password maximum is 72 because bcrypt only uses the first 72 BYTES of its
 * input. Longer passwords would silently be cut off, so we reject them instead.
 */
public record RegisterRequest(
		@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(min = 10, max = 72) String password) {
}
