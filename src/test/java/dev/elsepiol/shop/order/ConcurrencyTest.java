package dev.elsepiol.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.elsepiol.shop.auth.Role;
import dev.elsepiol.shop.auth.User;
import dev.elsepiol.shop.auth.UserRepository;
import dev.elsepiol.shop.inventory.InsufficientStockException;
import dev.elsepiol.shop.inventory.InventoryService;
import dev.elsepiol.shop.product.Product;
import dev.elsepiol.shop.product.ProductRepository;
import dev.elsepiol.shop.support.Concurrently;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The most important tests in the project. They fire many requests at the same instant and
 * check that the rules still hold: never sell more than we have, never create two orders for
 * one idempotency key, never deadlock, never return stock twice.
 *
 * They call the services directly (no HTTP) so thread timing is the only variable.
 */
@SpringBootTest
class ConcurrencyTest extends PostgresIntegrationTest {

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
	private PlatformTransactionManager transactionManager;

	private static String random(int length) {
		return UUID.randomUUID().toString().replace("-", "").substring(0, length);
	}

	private long newUser() {
		return users.saveAndFlush(new User("c-" + random(10) + "@example.com", "not-a-real-hash", Role.USER)).getId();
	}

	private long newProduct(int stock) {
		Product product = products.saveAndFlush(new Product("CC-" + random(12).toUpperCase(), "Thing", null, 1000));
		inventoryService.setStock(product.getId(), stock);
		return product.getId();
	}

	private int stockOf(long productId) {
		return jdbc.queryForObject("select quantity from inventory where product_id = ?", Integer.class, productId);
	}

	private static CreateOrderRequest oneOf(long productId) {
		return new CreateOrderRequest(List.of(new CreateOrderRequest.Item(productId, 1)));
	}

	@Test
	void fiftyBuyersFightOverTenItemsAndExactlyTenWin() throws Exception {
		long productId = newProduct(10);
		long userId = newUser();

		List<Object> results = Concurrently.runAtOnce(50,
				i -> () -> orderService.place(userId, UUID.randomUUID().toString(), oneOf(productId)));

		assertThat(Concurrently.countOf(results, PlacedOrder.class)).as("orders placed").isEqualTo(10);
		assertThat(Concurrently.countOf(results, InsufficientStockException.class)).as("turned away").isEqualTo(40);
		assertThat(results).as("no other kind of failure").hasSize(50);
		assertThat(stockOf(productId)).isZero();
		assertThat(jdbc.queryForObject("select coalesce(sum(quantity), 0) from order_items where product_id = ?",
				Long.class, productId)).as("units sold").isEqualTo(10L);
	}

	@Test
	void twentyIdenticalRetriesCreateExactlyOneOrder() throws Exception {
		long productId = newProduct(100);
		long userId = newUser();
		String key = "same-key-" + random(8);

		List<Object> results = Concurrently.runAtOnce(20, i -> () -> orderService.place(userId, key, oneOf(productId)));

		assertThat(Concurrently.countOf(results, PlacedOrder.class)).as("every retry got an answer").isEqualTo(20);
		Set<Long> orderIds = new HashSet<>();
		long replays = 0;
		for (Object result : results) {
			PlacedOrder placed = (PlacedOrder) result;
			orderIds.add(placed.order().id());
			if (placed.replayed()) {
				replays++;
			}
		}
		assertThat(orderIds).as("all retries returned the same order").hasSize(1);
		assertThat(replays).as("one original, the rest replays").isEqualTo(19);
		assertThat(stockOf(productId)).as("stock taken once, not twenty times").isEqualTo(99);
		assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ? and idempotency_key = ?",
				Long.class, userId, key)).isEqualTo(1L);
	}

	@Test
	void ordersListingTheSameProductsInOppositeOrderDoNotDeadlock() throws Exception {
		long a = newProduct(1000);
		long b = newProduct(1000);
		long userId = newUser();

		List<Object> results = Concurrently.runAtOnce(40, i -> () -> {
			CreateOrderRequest request = (i % 2 == 0)
					? new CreateOrderRequest(List.of(new CreateOrderRequest.Item(a, 1), new CreateOrderRequest.Item(b, 1)))
					: new CreateOrderRequest(List.of(new CreateOrderRequest.Item(b, 1), new CreateOrderRequest.Item(a, 1)));
			return orderService.place(userId, UUID.randomUUID().toString(), request);
		});

		assertThat(Concurrently.countOf(results, PlacedOrder.class)).as("all 40 succeeded").isEqualTo(40);
		assertThat(stockOf(a)).isEqualTo(960);
		assertThat(stockOf(b)).isEqualTo(960);
	}

	@Test
	void tenSimultaneousCancelsReturnTheStockExactlyOnce() throws Exception {
		long productId = newProduct(10);
		long userId = newUser();
		PlacedOrder placed = orderService.place(userId, UUID.randomUUID().toString(),
				new CreateOrderRequest(List.of(new CreateOrderRequest.Item(productId, 3))));
		assertThat(stockOf(productId)).isEqualTo(7);

		List<Object> results = Concurrently.runAtOnce(10,
				i -> () -> orderService.cancel(placed.order().id(), new CurrentUser(userId, false)));

		assertThat(Concurrently.countOf(results, OrderResponse.class)).as("one cancel wins").isEqualTo(1);
		assertThat(Concurrently.countOf(results, OrderNotCancellableException.class)).isEqualTo(9);
		assertThat(stockOf(productId)).as("stock returned once, not ten times").isEqualTo(10);
	}

	/**
	 * THIS IS THE BUG THE REAL CODE AVOIDS. It is a deliberately naive "read the stock, check it,
	 * write the new number" implementation. A barrier forces the unlucky timing that real traffic
	 * produces by chance: both buyers read the stock before either writes. Both see 1 in stock,
	 * both "sell" it, and we have sold two items while owning one. The test PASSES because it
	 * asserts that the bug happens; it exists so you can see it and understand why
	 * InventoryRepository.tryDecrement is written as one atomic statement.
	 */
	@Test
	void theNaiveReadThenWriteApproachSellsMoreThanWeHave() throws Exception {
		long productId = newProduct(1);
		CyclicBarrier bothHaveRead = new CyclicBarrier(2);
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		List<Object> results = Concurrently.runAtOnce(2, i -> () -> tx.execute(status -> {
			Integer inStock = jdbc.queryForObject("select quantity from inventory where product_id = ?",
					Integer.class, productId);
			try {
				bothHaveRead.await(15, TimeUnit.SECONDS); // both buyers now believe 1 is in stock
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
			if (inStock >= 1) {
				// Writes an ABSOLUTE number computed from the stale read. This is the lost update.
				jdbc.update("update inventory set quantity = ? where product_id = ?", inStock - 1, productId);
				return Boolean.TRUE; // "sold!"
			}
			return Boolean.FALSE;
		}));

		long sold = results.stream().filter(Boolean.TRUE::equals).count();
		assertThat(sold).as("both buyers were told they got the single item").isEqualTo(2);
		assertThat(stockOf(productId)).as("and the stock only went down by one").isZero();
	}

}
