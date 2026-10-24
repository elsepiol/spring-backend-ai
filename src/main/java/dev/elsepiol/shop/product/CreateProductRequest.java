package dev.elsepiol.shop.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * What a client may send. This is deliberately a separate type from the Product
 * entity: the API contract and the database table should be able to change
 * independently, and clients must not be able to set fields like id or createdAt.
 */
public record CreateProductRequest(
		@NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9-]+", message = "must contain only A-Z, 0-9 and -") String sku,
		@NotBlank @Size(max = 120) String name,
		@Size(max = 1000) String description,
		@PositiveOrZero long priceCents) {
}
