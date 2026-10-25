package dev.elsepiol.shop.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * PUT replaces every field a client may change. The SKU is not here: it identifies the
 * product to other systems (warehouses, invoices), so it never changes.
 */
public record UpdateProductRequest(
		@NotBlank @Size(max = 120) String name,
		@Size(max = 1000) String description,
		@PositiveOrZero long priceCents) {
}
