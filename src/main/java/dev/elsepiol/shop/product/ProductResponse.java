package dev.elsepiol.shop.product;

import java.time.Instant;

/** What the API returns for one product. */
public record ProductResponse(Long id, String sku, String name, String description, long priceCents,
		Instant createdAt) {

	public static ProductResponse from(Product p) {
		return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPriceCents(),
				p.getCreatedAt());
	}

}
