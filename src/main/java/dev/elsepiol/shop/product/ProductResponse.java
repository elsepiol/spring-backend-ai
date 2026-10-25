package dev.elsepiol.shop.product;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * What the API returns for one product. It is immutable, which is what makes it safe to keep
 * in a cache and hand to many requests at once (never cache JPA entities: they are mutable and
 * tied to the database session that loaded them).
 *
 * version is not part of the JSON body: clients get it as the ETag header instead.
 */
public record ProductResponse(Long id, String sku, String name, String description, long priceCents,
		Instant createdAt, @JsonIgnore long version) {

	public static ProductResponse from(Product p) {
		return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPriceCents(),
				p.getCreatedAt(), p.getVersion());
	}

}
