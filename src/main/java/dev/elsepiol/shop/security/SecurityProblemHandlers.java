package dev.elsepiol.shop.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Security runs in a servlet filter BEFORE Spring MVC, so ApiExceptionHandler
 * never sees these failures. This class writes the same problem-details JSON by
 * hand so that 401 and 403 look like every other error from the API.
 *
 * 401 = "I don't know who you are" (no token, bad token, expired token).
 * 403 = "I know who you are, and you may not do this".
 */
public class SecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		// Sets status 401 and the standard WWW-Authenticate: Bearer ... header.
		bearer.commence(request, response, authException);
		write(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
				"Authentication is required to access this resource");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
				"You do not have permission to perform this action");
	}

	private static void write(HttpServletResponse response, int status, String title, String detail)
			throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
				+ ",\"detail\":\"" + detail + "\"}");
	}

}
