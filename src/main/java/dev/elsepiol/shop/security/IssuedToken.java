package dev.elsepiol.shop.security;

import java.time.Instant;

public record IssuedToken(String value, Instant expiresAt) {
}
