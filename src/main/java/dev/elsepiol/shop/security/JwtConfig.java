package dev.elsepiol.shop.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Builds the pieces that create and check tokens. We use HS256: one shared secret
 * both signs and verifies. That is the simplest correct choice while a single
 * service does both jobs. When many services must verify tokens, you switch to an
 * asymmetric key pair (RS256/ES256) so only the issuer holds the private key.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

	/** Must match the default in application.properties; used only to warn. */
	static final String DEV_DEFAULT_SECRET = "dev-only-secret-change-me-dev-only-secret-change-me";

	private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

	@Bean
	public JwtEncoder jwtEncoder(JwtProperties properties) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
	}

	@Bean
	public JwtDecoder jwtDecoder(JwtProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		// The default validator checks expiry and not-before; we add the issuer check.
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
		return decoder;
	}

	/** bcrypt, work factor 10 (about 100 ms per hash on a normal CPU). Slow on purpose. */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	private SecretKey secretKey(JwtProperties properties) {
		byte[] bytes = properties.secret().getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			// HS256 needs a 256-bit key. A short secret is guessable; fail at startup, not in production.
			throw new IllegalStateException("app.security.jwt.secret must be at least 32 bytes long");
		}
		if (DEV_DEFAULT_SECRET.equals(properties.secret())) {
			log.warn("Using the built-in DEVELOPMENT JWT secret. Set JWT_SECRET before running anywhere real.");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

}
