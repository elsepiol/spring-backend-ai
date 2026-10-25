package dev.elsepiol.shop.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import dev.elsepiol.shop.auth.Role;
import dev.elsepiol.shop.auth.User;
import dev.elsepiol.shop.auth.UserRepository;
import dev.elsepiol.shop.inventory.InsufficientStockException;
import dev.elsepiol.shop.inventory.InventoryService;
import dev.elsepiol.shop.order.CreateOrderRequest;
import dev.elsepiol.shop.order.OrderEvent;
import dev.elsepiol.shop.order.OrderService;
import dev.elsepiol.shop.order.PlacedOrder;
import dev.elsepiol.shop.product.Product;
import dev.elsepiol.shop.product.ProductRepository;
import dev.elsepiol.shop.support.Concurrently;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The outbox worker, run by hand (the background poller is off in tests, see
 * PostgresIntegrationTest). Two extra handlers exist only here: one that counts deliveries and
 * one that fails on demand. Short delays keep retries fast.
 */
@SpringBootTest(properties = { "app.outbox.retry-base-delay=100ms", "app.outbox.max-attempts=3",
		"app.outbox.lease=2s" })
@Import(OutboxWorkerTest.TestHandlers.class)
class OutboxWorkerTest extends PostgresIntegrationTest {

	@TestConfiguration
	static class TestHandlers {

		@Bean
		CountingHandler countingHandler() {
			return new CountingHandler();
		}

		@Bean
		FlakyHandler flakyHandler() {
			return new FlakyHandler();
		}

	}

	/** Records how often each event was delivered; slow enough that racing workers overlap. */
	static class CountingHandler implements OutboxHandler {

		final Map<Long, AtomicInteger> deliveries = new ConcurrentHashMap<>();

		@Override
		public String type() {
			return "test.counting";
		}

		@Override
		public void handle(OutboxMessage message) throws InterruptedException {
			deliveries.computeIfAbsent(message.id(), id -> new AtomicInteger()).incrementAndGet();
			Thread.sleep(5);
		}

	}

	/** Fails while failuresLeft > 0, like a mail server that is down for a while. */
	static class FlakyHandler implements OutboxHandler {

		final AtomicInteger failuresLeft = new AtomicInteger();

		@Override
		public String type() {
			return "test.flaky";
		}

		@Override
		public void handle(OutboxMessage message) {
			if (failuresLeft.getAndDecrement() > 0) {
				throw new IllegalStateException("downstream unavailable");
			}
		}

	}

	@Autowired
	private OutboxWorker worker;

	@Autowired
	private OrderService orderService;

	@Autowired
	private InventoryService inventoryService;

	@Autowired
	private ProductRepository products;

	@Autowired
	private UserRepository users;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private CountingHandler counting;

	@Autowired
	private FlakyHandler flaky;

	private static String random() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	private long newUser() {
		return users.saveAndFlush(new User("ob-" + random() + "@example.com", "not-a-real-hash", Role.USER)).getId();
	}

	private long newProduct(int stock) {
		long id = products.saveAndFlush(new Product("OB-" + random().toUpperCase(), "Thing", null, 1250)).getId();
		inventoryService.setStock(id, stock);
		return id;
	}

	private PlacedOrder buy(long userId, long productId, int quantity) {
		return orderService.place(userId, UUID.randomUUID().toString(),
				new CreateOrderRequest(List.of(new CreateOrderRequest.Item(productId, quantity))));
	}

	/** Runs the worker until nothing is due. Other tests' pending events get processed too; that is fine. */
	private void drain() {
		while (worker.runOnce() > 0) {
			// keep going
		}
	}

	private long addEvent(String type) {
		return jdbc.queryForObject("insert into outbox_events (type, aggregate_id, payload) values (?, 0, '{}') "
				+ "returning id", Long.class, type);
	}

	private Map<String, Object> event(long id) {
		return jdbc.queryForMap("select status, attempts, last_error, available_at > now() as waiting "
				+ "from outbox_events where id = ?", id);
	}

	private List<String> notificationsOf(long userId) {
		return jdbc.queryForList("select message from notifications where user_id = ? order by id", String.class,
				userId);
	}

	@Test
	void anOrderAndItsEventCommitTogetherOrNotAtAll() {
		long userId = newUser();
		long mug = newProduct(1);

		PlacedOrder placed = buy(userId, mug, 1);
		Throwable outOfStock = catchThrowable(() -> buy(userId, mug, 1));

		assertThat(jdbc.queryForObject("select count(*) from outbox_events where type = ? and aggregate_id = ?",
				Long.class, OrderEvent.PLACED, placed.order().id())).isEqualTo(1L);
		assertThat(outOfStock).isInstanceOf(InsufficientStockException.class);
		// The failed order rolled back, and its event with it: nobody will be told about it.
		assertThat(jdbc.queryForObject("select count(*) from outbox_events e join orders o on o.id = e.aggregate_id "
				+ "where o.user_id = ?", Long.class, userId)).isEqualTo(1L);
	}

	@Test
	void theWorkerTurnsAPlacedOrderIntoANotification() {
		long userId = newUser();
		PlacedOrder placed = buy(userId, newProduct(5), 2);
		assertThat(notificationsOf(userId)).as("nothing yet: the request did not do it").isEmpty();

		drain();

		assertThat(notificationsOf(userId)).containsExactly("Order #" + placed.order().id() + " was placed. Total: 25.00");
		assertThat(jdbc.queryForObject("select status from outbox_events where type = ? and aggregate_id = ?",
				String.class, OrderEvent.PLACED, placed.order().id())).isEqualTo("DONE");
	}

	/**
	 * At-least-once delivery means a handler WILL see some events twice: the worker crashed after
	 * the handler ran but before it recorded DONE, or a lease ran out. Simulated here by putting
	 * a finished event back to PENDING.
	 */
	@Test
	void deliveringTheSameEventTwiceStillNotifiesOnce() {
		long userId = newUser();
		PlacedOrder placed = buy(userId, newProduct(5), 1);
		drain();

		jdbc.update("update outbox_events set status = 'PENDING', available_at = now() where type = ? and aggregate_id = ?",
				OrderEvent.PLACED, placed.order().id());
		drain();

		assertThat(notificationsOf(userId)).hasSize(1);
	}

	@Test
	void fourWorkersRacingNeverDeliverAnEventTwice() throws Exception {
		List<Long> ids = IntStream.range(0, 200).mapToObj(i -> addEvent("test.counting")).toList();

		Concurrently.runAtOnce(4, i -> () -> {
			drain();
			return null;
		});

		assertThat(ids).allSatisfy(id -> {
			assertThat(counting.deliveries.get(id)).as("deliveries of event " + id).hasValue(1);
			assertThat(event(id).get("status")).isEqualTo("DONE");
		});
	}

	@Test
	void aFailingHandlerIsRetriedLaterWithBackoffUntilItWorks() {
		flaky.failuresLeft.set(2);
		long id = addEvent("test.flaky");

		worker.runOnce();
		assertThat(event(id)).containsEntry("status", "PENDING").containsEntry("attempts", 1)
				.containsEntry("waiting", true) // not due again until the backoff has passed
				.containsEntry("last_error", "IllegalStateException: downstream unavailable");

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			worker.runOnce();
			assertThat(event(id).get("status")).isEqualTo("DONE");
		});
		assertThat(event(id).get("attempts")).as("two failures, then success").isEqualTo(3);
	}

	@Test
	void afterMaxAttemptsTheEventIsDeadNotRetriedForever() {
		flaky.failuresLeft.set(1_000);
		long id = addEvent("test.flaky");

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			worker.runOnce();
			assertThat(event(id).get("status")).isEqualTo("DEAD");
		});
		assertThat(event(id).get("attempts")).isEqualTo(3);
		flaky.failuresLeft.set(0);
	}

	@Test
	void anEventNoHandlerKnowsIsRetriedAndThenDeadNeverSilentlyDropped() {
		long id = addEvent("test.nobody-handles-this");

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			worker.runOnce();
			assertThat(event(id).get("status")).isEqualTo("DEAD");
		});
		assertThat((String) event(id).get("last_error")).contains("No handler for event type test.nobody-handles-this");
	}

	/** A worker claims an event and then "crashes": after its lease, another worker takes it over. */
	@Test
	void anEventClaimedByACrashedWorkerIsTakenOverWhenTheLeaseRunsOut() {
		long id = addEvent("test.counting");
		// Claim everything due with a 1 second lease and then do nothing, as a dead worker would.
		jdbc.query(OutboxWorker.CLAIM_SQL, (rs, row) -> rs.getLong("id"), 1.0, 10_000);

		worker.runOnce();
		assertThat(counting.deliveries).as("still leased to the dead worker").doesNotContainKey(id);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			worker.runOnce();
			assertThat(event(id).get("status")).isEqualTo("DONE");
		});
		assertThat(event(id).get("attempts")).as("the lost claim counts as an attempt").isEqualTo(2);
	}

}
