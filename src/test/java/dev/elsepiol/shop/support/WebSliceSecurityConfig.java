package dev.elsepiol.shop.support;

import dev.elsepiol.shop.security.SecurityConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * For @WebMvcTest slices. A slice test does not load our @Configuration classes, so
 * without this the web tests would run under Spring Boot's default security
 * (everything locked, CSRF on) instead of ours. We import the REAL security rules,
 * and replace the token decoder with one that accepts no real tokens: tests that
 * need a logged-in user use SecurityMockMvcRequestPostProcessors.jwt() instead.
 *
 * Use it with: @Import(WebSliceSecurityConfig.class)
 */
@TestConfiguration
@Import(SecurityConfig.class)
public class WebSliceSecurityConfig {

	@Bean
	JwtDecoder jwtDecoder() {
		return token -> {
			throw new BadJwtException("Real tokens are not accepted in web slice tests");
		};
	}

}
