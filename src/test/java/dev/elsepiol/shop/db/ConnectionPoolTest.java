package dev.elsepiol.shop.db;

import static dev.elsepiol.shop.support.TestUsers.admin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.elsepiol.shop.support.ApiClient;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What happens when every database connection is busy. The app gets its own tiny pool here
 * (2 connections, 300 ms wait) so the test can exhaust it on purpose.
 */
@SpringBootTest(properties = { "spring.datasource.hikari.maximum-pool-size=2",
		"spring.datasource.hikari.connection-timeout=300" })
@AutoConfigureMockMvc
class ConnectionPoolTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DataSource dataSource;

	@Test
	void whenThePoolIsExhaustedRequestsFailFastWith503ButCachedReadsStillWork() throws Exception {
		long id = new ApiClient(mvc).newProduct(100);
		mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk()); // now cached

		// Borrow every connection and hold on to them, like two slow requests would.
		try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
			long start = System.nanoTime();
			mvc.perform(get("/api/v1/products")) // needs a connection: none free
					.andExpect(status().isServiceUnavailable())
					.andExpect(header().string("Retry-After", "1"))
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
			long waitedMillis = (System.nanoTime() - start) / 1_000_000;
			assertThat(waitedMillis).as("failed after about connection-timeout, not 30 s").isLessThan(5_000);

			// The cache needs no connection, so this product is still served.
			mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());

			// Level 6: the instance reports itself NOT READY (no new traffic, please) but still ALIVE
			// (do not restart me: a restart would not bring connections back).
			mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
			mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
		}
		mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

		mvc.perform(get("/api/v1/products")).andExpect(status().isOk()); // connections back: normal again
	}

	@Test
	void thePoolSizeIsVisibleInTheMetrics() throws Exception {
		mvc.perform(get("/actuator/metrics/hikaricp.connections.max").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.measurements[0].value").value(2.0));
	}

}
