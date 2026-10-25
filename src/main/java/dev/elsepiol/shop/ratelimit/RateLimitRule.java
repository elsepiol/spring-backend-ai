package dev.elsepiol.shop.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.function.Function;
import org.springframework.http.HttpMethod;

/**
 * Which requests a limit applies to, and who it counts them for.
 *
 * @param name prefix of the bucket key, so different rules never share a bucket
 * @param method the HTTP method it applies to
 * @param paths exact paths it applies to
 * @param key who the requests are counted for: a client address, a user id...
 * @param limit how many per period
 */
public record RateLimitRule(String name, HttpMethod method, Set<String> paths,
		Function<HttpServletRequest, String> key, Limit limit) {

	boolean matches(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return method.matches(request.getMethod()) && paths.contains(path);
	}

}
