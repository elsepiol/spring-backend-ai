package dev.elsepiol.shop.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Plain unit tests of token creation and validation. No Spring context, no database. */
class JwtServiceTest {

	private static final String SECRET = "unit-test-secret-unit-test-secret-1234";

	private final JwtConfig config = new JwtConfig();

	private JwtProperties props(String secret, Duration ttl) {
		return new JwtProperties(secret, ttl, "shop");
	}

	private JwtService service(JwtProperties props) {
		return new JwtService(config.jwtEncoder(props), props, Clock.systemUTC());
	}

	private TokenSubject user() {
		return new TokenSubject("42", "a@example.com", "ADMIN");
	}

	@Test
	void issuedTokenCarriesTheExpectedClaims() {
		JwtProperties props = props(SECRET, Duration.ofMinutes(15));
		IssuedToken token = service(props).issue(user());

		Jwt jwt = config.jwtDecoder(props).decode(token.value());

		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsString("email")).isEqualTo("a@example.com");
		assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("shop");
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void theTokenNeverContainsThePasswordHash() {
		JwtProperties props = props(SECRET, Duration.ofMinutes(15));
		Jwt jwt = config.jwtDecoder(props).decode(service(props).issue(user()).value());

		assertThat(jwt.getClaims()).doesNotContainKey("password").doesNotContainKey("passwordHash");
		assertThat(jwt.getClaims().toString()).doesNotContain("hash");
	}

	@Test
	void aTamperedTokenIsRejected() {
		JwtProperties props = props(SECRET, Duration.ofMinutes(15));
		String token = service(props).issue(user()).value();
		String[] parts = token.split("\\.");
		// Swap the claims for a different, validly encoded payload but keep the old signature.
		String forgedClaims = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"sub\":\"1\",\"role\":\"ADMIN\",\"iss\":\"shop\"}".getBytes());
		String forged = parts[0] + "." + forgedClaims + "." + parts[2];

		JwtDecoder decoder = config.jwtDecoder(props);
		assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void aTokenSignedWithAnotherSecretIsRejected() {
		String token = service(props("another-secret-another-secret-123456", Duration.ofMinutes(15))).issue(user())
				.value();

		JwtDecoder decoder = config.jwtDecoder(props(SECRET, Duration.ofMinutes(15)));
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void anExpiredTokenIsRejected() {
		// Issued two hours ago with a 15 minute lifetime, so it expired 1h45m ago.
		// (The decoder allows 60 seconds of clock skew, so "expired 10 seconds ago" would still pass.)
		JwtProperties props = props(SECRET, Duration.ofMinutes(15));
		Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), java.time.ZoneOffset.UTC);
		String token = new JwtService(config.jwtEncoder(props), props, twoHoursAgo).issue(user()).value();

		assertThatThrownBy(() -> config.jwtDecoder(props).decode(token)).isInstanceOf(BadJwtException.class)
				.hasMessageContaining("expired");
	}

	@Test
	void aTokenFromAnotherIssuerIsRejected() {
		JwtProperties otherIssuer = new JwtProperties(SECRET, Duration.ofMinutes(15), "someone-else");
		String token = service(otherIssuer).issue(user()).value();

		JwtDecoder decoder = config.jwtDecoder(props(SECRET, Duration.ofMinutes(15)));
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void aShortSecretIsRefusedAtStartup() {
		assertThatThrownBy(() -> config.jwtEncoder(props("too-short", Duration.ofMinutes(1))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32 bytes");
	}

	@Test
	void issuedTimeComesFromTheInjectedClock() {
		JwtProperties props = props(SECRET, Duration.ofMinutes(15));
		Instant fixed = Instant.parse("2030-01-01T00:00:00Z");
		JwtService service = new JwtService(config.jwtEncoder(props), props, Clock.fixed(fixed, java.time.ZoneOffset.UTC));

		assertThat(service.issue(user()).expiresAt()).isEqualTo(fixed.plus(Duration.ofMinutes(15)));
	}

}
