package dev.elsepiol.shop.order;

import java.time.Instant;
import java.util.List;

public record OrderResponse(long id, String status, long totalCents, Instant createdAt,
		List<OrderItemResponse> items) {

	/** Must be called inside a transaction: it reads the lazily loaded items. */
	static OrderResponse from(CustomerOrder order) {
		return new OrderResponse(order.getId(), order.getStatus().name(), order.getTotalCents(),
				order.getCreatedAt(), order.getItems().stream().map(OrderItemResponse::from).toList());
	}

}
