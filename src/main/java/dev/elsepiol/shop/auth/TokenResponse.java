package dev.elsepiol.shop.auth;

/** What a successful login returns. The client sends accessToken as "Authorization: Bearer ...". */
public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
