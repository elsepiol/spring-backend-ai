package dev.elsepiol.shop.inventory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Integer (not int) so that a missing field is rejected instead of silently becoming 0. */
public record SetStockRequest(@NotNull @Min(0) @Max(1_000_000) Integer quantity) {
}
