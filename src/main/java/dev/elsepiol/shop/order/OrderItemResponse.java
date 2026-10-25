package dev.elsepiol.shop.order;

public record OrderItemResponse(long productId, int quantity, long unitPriceCents) {

	static OrderItemResponse from(OrderItem item) {
		return new OrderItemResponse(item.getProductId(), item.getQuantity(), item.getUnitPriceCents());
	}

}
