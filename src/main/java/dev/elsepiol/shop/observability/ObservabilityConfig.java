package dev.elsepiol.shop.observability;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ObservabilityConfig {

	/**
	 * Registered here, not as a @Component filter, so web slice tests (which have no tracer) do not
	 * pick it up. Order: Spring's ServerHttpObservationFilter runs at HIGHEST_PRECEDENCE + 1 and
	 * opens the span; Spring Security's filter chain runs at -100. Between the two is right.
	 */
	@Bean
	FilterRegistrationBean<TraceIdHeaderFilter> traceIdHeaderFilter(Tracer tracer) {
		FilterRegistrationBean<TraceIdHeaderFilter> registration = new FilterRegistrationBean<>(
				new TraceIdHeaderFilter(tracer));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
		return registration;
	}

}
