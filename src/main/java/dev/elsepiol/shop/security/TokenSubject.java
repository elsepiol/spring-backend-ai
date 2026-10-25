package dev.elsepiol.shop.security;

/**
 * Who a token is issued for: exactly what goes into its claims, nothing more.
 *
 * Level 6: JwtService used to take the auth module's User entity, while auth used JwtService to
 * issue tokens, so each module depended on the other (a cycle). Now security defines the small
 * type it needs and auth fills it in: the dependency points one way, auth -> security.
 * ModularityTest keeps it that way.
 */
public record TokenSubject(String subject, String email, String role) {
}
