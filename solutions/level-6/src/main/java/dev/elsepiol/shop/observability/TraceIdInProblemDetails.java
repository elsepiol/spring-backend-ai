package dev.elsepiol.shop.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * SOLUTION (Exercise 6B). Adds "traceId" to every problem-details error body, just before it is
 * written, whichever handler produced it. Users copy error bodies into support tickets far more
 * often than response headers.
 *
 * ObjectProvider, not Tracer: @WebMvcTest slices load @RestControllerAdvice classes but have no
 * tracing, so a required Tracer would break them.
 */
@RestControllerAdvice
public class TraceIdInProblemDetails implements ResponseBodyAdvice<Object> {

	private final ObjectProvider<Tracer> tracer;

	public TraceIdInProblemDetails(ObjectProvider<Tracer> tracer) {
		this.tracer = tracer;
	}

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return true; // decided per body below: only ProblemDetail bodies are changed
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
			Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
			ServerHttpResponse response) {
		if (body instanceof ProblemDetail problem) {
			Tracer current = tracer.getIfAvailable();
			Span span = current != null ? current.currentSpan() : null;
			if (span != null) {
				problem.setProperty("traceId", span.context().traceId());
			}
		}
		return body;
	}

}
