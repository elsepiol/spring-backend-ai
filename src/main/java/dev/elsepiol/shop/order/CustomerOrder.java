package dev.elsepiol.shop.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Named CustomerOrder, not Order: ORDER is a reserved word in both SQL and JPQL
 * ("order by"), and an entity called Order causes confusing query errors.
 */
@Entity
@Table(name = "orders")
public class CustomerOrder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// A plain id, not a @ManyToOne to User: orders do not need the user object, and not
	// loading it avoids an extra query per order.
	@Column(name = "user_id", nullable = false, updatable = false)
	private long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status;

	@Column(name = "total_cents", nullable = false, updatable = false)
	private long totalCents;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false, updatable = false)
	private String requestHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	// cascade ALL: saving the order saves its items. mappedBy: the foreign key lives on order_items.
	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
	@OrderBy("id")
	private List<OrderItem> items = new ArrayList<>();

	protected CustomerOrder() {
	}

	public CustomerOrder(long userId, String idempotencyKey, String requestHash, long totalCents) {
		this.userId = userId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.totalCents = totalCents;
		this.status = OrderStatus.PLACED;
		this.createdAt = Instant.now();
	}

	public void addItem(OrderItem item) {
		item.setOrder(this);
		items.add(item);
	}

	public Long getId() {
		return id;
	}

	public long getUserId() {
		return userId;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public long getTotalCents() {
		return totalCents;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public List<OrderItem> getItems() {
		return items;
	}

}
