package dev.elsepiol.shop.security;

import dev.elsepiol.shop.auth.User;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Creates signed access tokens. A JWT is three base64 parts: header.claims.signature.
 * The claims are readable by anyone who holds the token (they are encoded, NOT
 * encrypted), so never put secrets in them. The signature proves the claims were
 * not changed after we issued them.
 */
@Service
public class JwtService {

	private final JwtEncoder encoder;
	private final JwtProperties properties;
	private final Clock clock;

	public JwtService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	public IssuedToken issue(User user) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.ttl());

		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.issuer())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.subject(String.valueOf(user.getId())) // "sub": who this token is about
				.claim("email", user.getEmail())
				.claim("role", user.getRole().name())
				.build();

		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, expiresAt);
	}

}
