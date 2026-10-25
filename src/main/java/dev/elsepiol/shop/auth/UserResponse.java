package dev.elsepiol.shop.auth;

/** Never includes the password hash. */
public record UserResponse(Long id, String email, String role) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getRole().name());
	}

}
