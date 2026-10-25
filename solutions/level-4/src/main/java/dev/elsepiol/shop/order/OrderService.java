package dev.elsepiol.shop.order;

import dev.elsepiol.shop.inventory.InsufficientStockException;
import dev.elsepiol.shop.inventory.InventoryRepository;
import dev.elsepiol.shop.product.Cursor;
import dev.elsepiol.shop.product.CursorPage;
import dev.elsepiol.shop.product.Product;
import dev.elsepiol.shop.product.ProductRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The heart of Level 3. Read the comments in place(): every line there exists because of a
 * specific thing that goes wrong under concurrency.
 */
@Service
public class OrderService {

	private final CustomerOrderRepository orders;
	private final ProductRepository products;
	private final InventoryRepository inventory;
	private final TransactionTemplate writeTx;
	private final TransactionTemplate readTx;

	public OrderService(CustomerOrderRepository orders, ProductRepository products, InventoryRepository inventory,
			PlatformTransactionManager transactionManager) {
		this.orders = orders;
		this.products = products;
		this.inventory = inventory;
		// We open transactions by hand here (instead of @Transactional) because place() must
		// catch an exception AFTER its transaction has rolled back and then look again.
		// @Transactional wraps the whole method, so the catch would run inside the doomed
		// transaction, and in PostgreSQL a failed statement poisons the rest of that transaction.
		this.writeTx = new TransactionTemplate(transactionManager);
		this.readTx = new TransactionTemplate(transactionManager);
		this.readTx.setReadOnly(true);
	}

	public PlacedOrder place(long userId, String rawKey, CreateOrderRequest request) {
		String key = requireKey(rawKey);

		// Merge duplicate lines and sort by product id. TreeMap iterates in ascending key order,
		// which gives every order the SAME stock-locking order (see the deadlock note below).
		Map<Long, Integer> wanted = new TreeMap<>();
		request.items().forEach(i -> wanted.merge(i.productId(), i.quantity(), Integer::sum));
		String hash = fingerprint(wanted);

		// 1. Is this a retry of something we already did? Then answer with the original result.
		Optional<PlacedOrder> earlier = findEarlier(userId, key, hash);
		if (earlier.isPresent()) {
			return earlier.get();
		}

		try {
			// 2. Do the whole purchase in ONE transaction: all of it happens, or none of it.
			OrderResponse created = writeTx.execute(status -> createOrder(userId, key, hash, wanted));
			return new PlacedOrder(created, false);
		}
		catch (DataIntegrityViolationException ex) {
			// 3. Two requests with the same key raced. The database let one insert win; ours
			// failed on the UNIQUE constraint and its transaction (stock changes included) was
			// rolled back. The winner has committed by now, so return ITS order.
			return findEarlier(userId, key, hash).orElseThrow(() -> ex);
		}
	}

	private OrderResponse createOrder(long userId, String key, String hash, Map<Long, Integer> wanted) {
		Map<Long, Product> found = products.findAllById(wanted.keySet())
				.stream()
				.collect(Collectors.toMap(Product::getId, Function.identity()));

		long total = 0;
		List<OrderItem> items = new ArrayList<>();
		for (Map.Entry<Long, Integer> line : wanted.entrySet()) {
			Product product = found.get(line.getKey());
			if (product == null) {
				throw new InvalidOrderException("Product " + line.getKey() + " does not exist");
			}
			try {
				total = Math.addExact(total, Math.multiplyExact(product.getPriceCents(), (long) line.getValue()));
			}
			catch (ArithmeticException overflow) {
				throw new InvalidOrderException("Order total is too large");
			}
			// Snapshot the price now: later price changes must not change what this customer paid.
			items.add(new OrderItem(product.getId(), line.getValue(), product.getPriceCents()));
		}

		CustomerOrder order = new CustomerOrder(userId, key, hash, total);
		items.forEach(order::addItem);

		// Insert the order FIRST. This "claims" the idempotency key. A concurrent request with
		// the same key blocks right here until we commit or roll back, WITHOUT holding any stock
		// locks yet, and then fails on the UNIQUE constraint instead of taking stock twice.
		orders.saveAndFlush(order);

		// Now take the stock, one atomic statement per product, in ascending product id order.
		// If any product is short, we throw, and the transaction rolls back EVERYTHING above
		// (the order row and any stock already taken). No half-orders, and the key is not
		// consumed by a failed attempt, so the client may retry the same key later.
		for (Map.Entry<Long, Integer> line : wanted.entrySet()) {
			if (inventory.tryDecrement(line.getKey(), line.getValue()) == 0) {
				throw new InsufficientStockException(line.getKey());
			}
		}
		return OrderResponse.from(order);
	}

	private Optional<PlacedOrder> findEarlier(long userId, String key, String hash) {
		return Optional.ofNullable(readTx.execute(status -> orders.findByUserIdAndIdempotencyKey(userId, key)
				.map(existing -> {
					if (!existing.getRequestHash().equals(hash)) {
						throw new IdempotencyKeyReuseException();
					}
					return new PlacedOrder(OrderResponse.from(existing), true);
				})
				.orElse(null)));
	}

	@Transactional(readOnly = true)
	public OrderResponse get(long id, CurrentUser caller) {
		return OrderResponse.from(visibleOrder(id, caller));
	}

	@Transactional(readOnly = true)
	public Page<OrderResponse> listMine(long userId, Pageable pageable) {
		// Mapping touches each order's lazy items. hibernate.default_batch_fetch_size (see
		// application.properties) loads them for a whole page in ONE query instead of one query
		// per order (the "N+1 query problem").
		return orders.findByUserId(userId, pageable).map(OrderResponse::from);
	}

	/**
	 * SOLUTION (Exercise 4A). Same shape as ProductService.scrollByPrice: one row more than the
	 * page tells us whether there is a next page, and the cursor is the id of the last order shown.
	 */
	@Transactional(readOnly = true)
	public CursorPage<OrderResponse> scrollMine(long userId, String cursor, int size) {
		long before = cursor == null ? Long.MAX_VALUE : Cursor.decode(cursor, 1)[0];
		List<CustomerOrder> rows = orders.findByUserIdAndIdLessThanOrderByIdDesc(userId, before, Limit.of(size + 1));

		boolean more = rows.size() > size;
		List<CustomerOrder> page = more ? rows.subList(0, size) : rows;
		String next = more ? Cursor.encode(page.get(page.size() - 1).getId()) : null;
		// Batch fetching (default_batch_fetch_size) loads the items of the whole page in one query.
		return new CursorPage<>(page.stream().map(OrderResponse::from).toList(), next);
	}

	@Transactional
	public OrderResponse cancel(long id, CurrentUser caller) {
		CustomerOrder order = visibleOrder(id, caller);
		// Copy the items BEFORE the status update below, which clears Hibernate's cache.
		List<OrderItem> items = new ArrayList<>(order.getItems());

		// Atomic: only one of several simultaneous cancels can move PLACED -> CANCELLED.
		if (orders.transition(id, OrderStatus.PLACED, OrderStatus.CANCELLED) == 0) {
			throw new OrderNotCancellableException(id);
		}

		// Only the winner reaches here, so stock is returned exactly once. Same ascending order.
		items.stream()
				.sorted(Comparator.comparingLong(OrderItem::getProductId))
				.forEach(item -> inventory.increment(item.getProductId(), item.getQuantity()));

		return OrderResponse.from(orders.findById(id).orElseThrow());
	}

	private CustomerOrder visibleOrder(long id, CurrentUser caller) {
		return orders.findById(id)
				.filter(order -> caller.canSee(order.getUserId()))
				.orElseThrow(() -> new OrderNotFoundException(id));
	}

	private static String requireKey(String key) {
		if (key == null || key.isBlank() || key.length() > 100) {
			throw new InvalidOrderException("Idempotency-Key header is required (1 to 100 characters)");
		}
		return key.trim();
	}

	/** A short fingerprint of what was asked for, so a reused key with a different request is detected. */
	private static String fingerprint(Map<Long, Integer> wanted) {
		StringBuilder canonical = new StringBuilder();
		wanted.forEach((productId, quantity) -> canonical.append(productId).append(':').append(quantity).append(';'));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // every Java runtime must provide SHA-256
		}
	}

}
