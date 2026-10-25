package dev.elsepiol.shop.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stock for one product. The id IS the product id (one row per product). */
@Entity
@Table(name = "inventory")
public class Inventory {

	@Id
	@Column(name = "product_id")
	private Long productId;

	@Column(nullable = false)
	private int quantity;

	protected Inventory() {
	}

	public Long getProductId() {
		return productId;
	}

	public int getQuantity() {
		return quantity;
	}

}
