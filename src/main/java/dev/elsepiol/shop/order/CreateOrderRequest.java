package dev.elsepiol.shop.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(@NotEmpty @Size(max = 50) List<@Valid Item> items) {

	/** One line of the order. The caps keep a single request from being absurdly large. */
	public record Item(@Positive long productId, @Min(1) @Max(100) int quantity) {
	}

}
