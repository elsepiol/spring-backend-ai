package dev.elsepiol.shop.observability;

import static dev.elsepiol.shop.support.TestUsers.admin;
import static dev.elsepiol.shop.support.TestUsers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.elsepiol.shop.support.ApiClient;
import dev.elsepiol.shop.support.ApiClient.Account;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Traces you can follow, and metrics Prometheus can scrape.
 *
 * Spring Boot tests switch metric exporters (like Prometheus) and trace propagation OFF by
 * default, so that ordinary tests do not depend on them. @AutoConfigureMetrics and
 * @AutoConfigureTracing switch them back on for this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@AutoConfigureTracing
class ObservabilityTest extends PostgresIntegrationTest {

	private static final String TRACE_ID = "[0-9a-f]{32}";

	@Autowired
	private MockMvc mvc;

	@Test
	void everyResponseCarriesItsTraceIdEvenErrorsAndSecurityRefusals() throws Exception {
		mvc.perform(get("/api/v1/ping")).andExpect(header().string(TraceIdHeaderFilter.HEADER,
				org.hamcrest.Matchers.matchesPattern(TRACE_ID)));
		mvc.perform(get("/api/v1/products/987654321")).andExpect(status().isNotFound())
				.andExpect(header().string(TraceIdHeaderFilter.HEADER, org.hamcrest.Matchers.matchesPattern(TRACE_ID)));
		mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized())
				.andExpect(header().string(TraceIdHeaderFilter.HEADER, org.hamcrest.Matchers.matchesPattern(TRACE_ID)));
	}

	@Test
	void twoRequestsAreTwoTraces() throws Exception {
		String first = mvc.perform(get("/api/v1/ping")).andReturn().getResponse().getHeader(TraceIdHeaderFilter.HEADER);
		String second = mvc.perform(get("/api/v1/ping")).andReturn().getResponse().getHeader(TraceIdHeaderFilter.HEADER);

		assertThat(first).isNotEqualTo(second);
	}

	/**
	 * Distributed tracing: a caller (a gateway, another service, a browser with tracing) sends a
	 * W3C traceparent header, and we CONTINUE its trace instead of starting a new one, so one id
	 * covers the request's whole path through every system.
	 */
	@Test
	void anIncomingTraceparentIsContinued() throws Exception {
		mvc.perform(get("/api/v1/ping").header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
				.andExpect(header().string(TraceIdHeaderFilter.HEADER, "4bf92f3577b34da6a3ce929d0e0e4736"));
	}

	@Test
	void prometheusSeesLatencyHistogramsAndOrderOutcomes() throws Exception {
		ApiClient api = new ApiClient(mvc);
		Account alice = api.newUser();
		long mug = api.newProductWithStock(100, 1);
		api.placeOrder(alice, ApiClient.newKey(), mug, 1).andExpect(status().isCreated());
		api.placeOrder(alice, ApiClient.newKey(), mug, 1).andExpect(status().isConflict());

		String scrape = mvc.perform(get("/actuator/prometheus").with(admin())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(scrape).contains("http_server_requests_seconds_bucket{") // histograms, for p95/p99
				.containsPattern("shop_orders_total\\{application=\"shop\",outcome=\"created\"} [1-9]")
				.containsPattern("shop_orders_total\\{application=\"shop\",outcome=\"out_of_stock\"} [1-9]")
				.containsPattern("hikaricp_connections_active\\{.*pool=\"shop-db\"");
	}

	@Test
	void theScrapeEndpointIsNotPublic() throws Exception {
		mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/prometheus").with(user())).andExpect(status().isForbidden());
	}

}
