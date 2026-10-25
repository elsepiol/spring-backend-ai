package dev.elsepiol.shop.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the rules before the request reaches Spring MVC. SecurityConfig places it AFTER the
 * authorization filter: requests that are refused anyway (no token, wrong role) never reach it
 * and cost nobody quota, and for those that do, the authenticated user is already known.
 *
 * Denied: 429 Too Many Requests, with Retry-After saying how many seconds until a retry can
 * succeed, as problem-details JSON like every other error. Not created as a Spring bean on
 * purpose, so Spring Boot does not ALSO register it as a plain servlet filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private final RateLimitRules rules;

	public RateLimitFilter(RateLimitRules rules) {
		this.rules = rules;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		for (RateLimitRule rule : rules.rules()) {
			if (rule.matches(request)) {
				Decision decision = rules.limiter().tryAcquire(rule.name() + ":" + rule.key().apply(request), rule.limit());
				if (!decision.allowed()) {
					reject(response, decision);
					return;
				}
			}
		}
		chain.doFilter(request, response);
	}

	private static void reject(HttpServletResponse response, Decision decision) throws IOException {
		response.setStatus(429);
		response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
				+ "\"detail\":\"Too many requests. Retry after " + decision.retryAfterSeconds() + " seconds\"}");
	}

}
