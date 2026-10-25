package dev.elsepiol.shop.inventory;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** delta is relative: +5 adds five units, -2 removes two. */
public record AdjustStockRequest(@NotNull @Min(-1_000_000) @Max(1_000_000) Integer delta) {

	@AssertTrue(message = "delta must not be zero")
	public boolean isNonZero() {
		return delta == null || delta != 0;
	}

}
