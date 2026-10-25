package dev.elsepiol.shop.notification;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.elsepiol.shop.support.ApiClient;
import dev.elsepiol.shop.support.ApiClient.Account;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole asynchronous path with the REAL background poller switched on: place an order, and
 * a moment later the notification appears. "A moment later" is the point: the response to the
 * purchase comes back before the notification exists (eventual consistency).
 *
 * @DirtiesContext closes this context after the class, so its poller stops and cannot take
 * events that later test classes want to process themselves.
 */
@SpringBootTest
@TestPropertySource(properties = { "app.outbox.worker.enabled=true", "app.outbox.poll-interval-ms=100" })
@AutoConfigureMockMvc
@DirtiesContext
class NotificationApiTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void aPlacedOrderShowsUpInMyNotificationsShortlyAfterAndNotInAnyoneElses() throws Exception {
		ApiClient api = new ApiClient(mvc);
		Account alice = api.newUser();
		Account bob = api.newUser();
		long mug = api.newProductWithStock(999, 5);
		String body = api.placeOrder(alice, ApiClient.newKey(), mug, 1).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long orderId = ((Number) JsonPath.read(body, "$.id")).longValue();

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc
				.perform(get("/api/v1/notifications").header("Authorization", alice.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].message").value("Order #" + orderId + " was placed. Total: 9.99")));

		mvc.perform(get("/api/v1/notifications").header("Authorization", bob.bearer()))
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void notificationsNeedAToken() throws Exception {
		mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
	}

}
