package dev.elsepiol.shop.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// LAZY: do not load the order again every time we load an item.
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id")
	private CustomerOrder order;

	@Column(name = "product_id", nullable = false)
	private long productId;

	@Column(nullable = false)
	private int quantity;

	@Column(name = "unit_price_cents", nullable = false)
	private long unitPriceCents;

	protected OrderItem() {
	}

	public OrderItem(long productId, int quantity, long unitPriceCents) {
		this.productId = productId;
		this.quantity = quantity;
		this.unitPriceCents = unitPriceCents;
	}

	void setOrder(CustomerOrder order) {
		this.order = order;
	}

	public long getProductId() {
		return productId;
	}

	public int getQuantity() {
		return quantity;
	}

	public long getUnitPriceCents() {
		return unitPriceCents;
	}

}
