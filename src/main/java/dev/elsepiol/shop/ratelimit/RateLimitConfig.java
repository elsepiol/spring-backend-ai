package dev.elsepiol.shop.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** The rate limits of this API. SecurityConfig puts them into the filter chain. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

	@Bean
	RateLimitRules rateLimitRules(RateLimitProperties properties) {
		return new RateLimitRules(new RateLimiter(), List.of(
				// Password guessing and mass sign-ups. Counted per client address, because there is
				// no logged-in user yet. Wrong passwords count too: that is the point.
				new RateLimitRule("auth", HttpMethod.POST, Set.of("/api/v1/auth/login", "/api/v1/auth/register"),
						RateLimitConfig::clientAddress, properties.auth())));
	}

	/**
	 * The address the connection came from. Behind a load balancer or proxy this is the PROXY's
	 * address, and the client's is in the X-Forwarded-For header. Never read that header blindly
	 * (any client can send one); let Spring Boot read it only from a proxy you trust, with
	 * server.forward-headers-strategy=native or framework, and then getRemoteAddr() is the client.
	 */
	static String clientAddress(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

}
