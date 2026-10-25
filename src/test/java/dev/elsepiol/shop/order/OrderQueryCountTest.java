package dev.elsepiol.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.elsepiol.shop.support.ApiClient;
import dev.elsepiol.shop.support.ApiClient.Account;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Counts the SQL statements Hibernate runs for one request. Listing 10 orders, each with
 * items, would take 1 (orders) + 1 (count) + 10 (items, one per order) = 12 statements
 * without batching: the "N+1 problem". With hibernate.default_batch_fetch_size it is a handful,
 * and stays a handful as the page grows. This test fails if someone removes that setting.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class OrderQueryCountTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void listingTenOrdersDoesNotRunOneQueryPerOrder() throws Exception {
		ApiClient api = new ApiClient(mvc);
		Account alice = api.newUser();
		long mug = api.newProductWithStock(100, 100);
		for (int i = 0; i < 10; i++) {
			api.placeOrder(alice, ApiClient.newKey(), mug, 1).andExpect(status().isCreated());
		}
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		mvc.perform(get("/api/v1/orders").header("Authorization", alice.bearer()).param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(10))
				.andExpect(jsonPath("$.items[9].items.length()").value(1));

		long statements = statistics.getPrepareStatementCount();
		assertThat(statements).as("SQL statements for one page of 10 orders").isLessThanOrEqualTo(5);
	}

}
