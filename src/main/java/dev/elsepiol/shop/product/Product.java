package dev.elsepiol.shop.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A JPA entity: one object of this class maps to one row of the "products" table.
 * Money is stored as a whole number of minor units (cents/paise), never as a
 * floating point number, because 0.1 + 0.2 != 0.3 in binary floating point.
 */
@Entity
@Table(name = "products")
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY) // the database assigns the id
	private Long id;

	@Column(nullable = false, updatable = false)
	private String sku;

	@Column(nullable = false)
	private String name;

	private String description;

	@Column(name = "price_cents", nullable = false)
	private long priceCents;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** JPA needs a no-argument constructor to create objects when reading rows. */
	protected Product() {
	}

	public Product(String sku, String name, String description, long priceCents) {
		this.sku = sku;
		this.name = name;
		this.description = description;
		this.priceCents = priceCents;
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public long getPriceCents() {
		return priceCents;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
