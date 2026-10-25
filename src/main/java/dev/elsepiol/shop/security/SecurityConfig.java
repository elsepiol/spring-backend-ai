package dev.elsepiol.shop.security;

import dev.elsepiol.shop.ratelimit.RateLimitFilter;
import dev.elsepiol.shop.ratelimit.RateLimitRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Who may call what. Rules are checked top to bottom and the FIRST match wins,
 * so specific rules go first and the catch-all goes last. The last line makes
 * the whole API "deny by default": a new endpoint you forget to list is
 * protected, not open.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitRules rateLimits) throws Exception {
		SecurityProblemHandlers problemHandlers = new SecurityProblemHandlers();

		http
				// CSRF protects browsers that send cookies automatically. We use a bearer token
				// that the client attaches explicitly, so there is nothing for an attacker to ride on.
				.csrf(csrf -> csrf.disable())
				// No server-side session: every request carries its own proof of identity.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/error", "/actuator/health/**", "/actuator/info", "/api/v1/ping",
								"/api/v1/greetings/**")
						.permitAll()
						// Metrics reveal traffic and internals: operators only.
						.requestMatchers("/actuator/**")
						.hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login")
						.permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**")
						.permitAll()
						.requestMatchers("/api/v1/products/**")
						.hasRole("ADMIN") // anything else on products: POST, PUT, DELETE
						.anyRequest()
						.authenticated())
				// This app is a "resource server": it accepts JWT bearer tokens.
				.oauth2ResourceServer(oauth -> oauth
						.authenticationEntryPoint(problemHandlers)
						.accessDeniedHandler(problemHandlers)
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
				// Level 5: rate limits run after authorization, so the caller is known and refused
				// requests do not use up anyone's quota.
				.addFilterAfter(new RateLimitFilter(rateLimits), AuthorizationFilter.class);

		return http.build();
	}

	/** Turns the token's "role" claim ("ADMIN") into the authority "ROLE_ADMIN" that hasRole("ADMIN") checks. */
	private static JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("role");
		authorities.setAuthorityPrefix("ROLE_");

		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

}
