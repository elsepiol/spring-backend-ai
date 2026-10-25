package dev.elsepiol.shop.order;

/** The result of placing an order, and whether it was a replay of an earlier identical request. */
public record PlacedOrder(OrderResponse order, boolean replayed) {
}
