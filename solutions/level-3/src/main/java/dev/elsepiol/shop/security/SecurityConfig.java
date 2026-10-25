package dev.elsepiol.shop.security;

import dev.elsepiol.shop.ratelimit.RateLimitFilter;
import dev.elsepiol.shop.ratelimit.RateLimitRules;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
				// Frontend level: CORS runs FIRST, so a browser's preflight request (OPTIONS, which never
				// carries a token) is answered before authentication could refuse it.
				.cors(Customizer.withDefaults())
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
						.requestMatchers("/api/v1/products/**", "/api/v1/admin/**")
						.hasRole("ADMIN") // anything else on products (POST, PUT, DELETE) and the admin area
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

	/**
	 * Frontend level. Which OTHER web origins may call this API from a browser (CORS).
	 *
	 * Browsers enforce the same-origin policy: JavaScript from https://shop.example may only read
	 * responses from https://shop.example. To call an API on another origin, the browser first
	 * asks it ("preflight": OPTIONS with Origin and the method and headers it wants to send) and
	 * goes ahead only if the API answers with matching Access-Control-Allow-* headers.
	 *
	 * Empty by default: no other origin may call us. That is right when the frontend is served
	 * from the same origin (the Vite dev proxy, or nginx in front of both in production), which
	 * needs no CORS at all. Set CORS_ALLOWED_ORIGINS only if the frontend lives elsewhere.
	 * CORS protects the browser's user, not the API: curl and servers ignore it entirely.
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource(
			@Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(allowedOrigins); // exact origins, never "*" for an API with logins
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
		// Request headers the frontend may send. Anything else fails the preflight.
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "If-Match",
				"If-None-Match", "traceparent"));
		// Response headers JavaScript may READ. Without this list the browser hides all but a few
		// basic ones, and the frontend could not see ETag, Retry-After or the trace id.
		cors.setExposedHeaders(List.of("ETag", "Location", "Retry-After", "X-Trace-Id", "Idempotent-Replayed"));
		// The token travels in a header, not a cookie, so no credentials mode is needed.
		cors.setAllowCredentials(false);
		cors.setMaxAge(Duration.ofHours(1)); // browsers may cache a preflight answer this long
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
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
