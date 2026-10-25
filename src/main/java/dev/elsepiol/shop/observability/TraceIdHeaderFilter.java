package dev.elsepiol.shop.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request's trace id into an X-Trace-Id response header. When a user reports "it
 * failed", that id finds every log line and every span of exactly that request, across all
 * instances (and, with tracing in other services, across services too).
 *
 * Runs right after Spring's observation filter, which starts the trace, and before the security
 * filters, so 401 and 403 responses carry the header too.
 */
public class TraceIdHeaderFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Trace-Id";

	private final Tracer tracer;

	public TraceIdHeaderFilter(Tracer tracer) {
		this.tracer = tracer;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Span span = tracer.currentSpan();
		if (span != null) {
			response.setHeader(HEADER, span.context().traceId());
		}
		chain.doFilter(request, response);
	}

}
