package dev.elsepiol.shop.order;

import org.springframework.security.oauth2.jwt.Jwt;

/** Who is calling, read from the validated token. */
public record CurrentUser(long id, boolean admin) {

	public static CurrentUser from(Jwt jwt) {
		return new CurrentUser(Long.parseLong(jwt.getSubject()), "ADMIN".equals(jwt.getClaimAsString("role")));
	}

	/** Admins may see everything; everyone else only their own. */
	public boolean canSee(long ownerId) {
		return admin || id == ownerId;
	}

}
