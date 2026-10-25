package dev.elsepiol.shop.notification;

import dev.elsepiol.shop.order.OrderEvent;
import dev.elsepiol.shop.outbox.OutboxHandler;
import dev.elsepiol.shop.outbox.OutboxMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** SOLUTION (Exercise 5B). Same shape as OrderPlacedNotifier: idempotent through recordOnce. */
@Component
public class OrderCancelledNotifier implements OutboxHandler {

	private final NotificationService notifications;
	private final JsonMapper json;

	public OrderCancelledNotifier(NotificationService notifications, JsonMapper json) {
		this.notifications = notifications;
		this.json = json;
	}

	@Override
	public String type() {
		return OrderEvent.CANCELLED;
	}

	@Override
	public void handle(OutboxMessage message) {
		OrderEvent event = json.readValue(message.payload(), OrderEvent.class);
		notifications.recordOnce(message.id(), event.userId(), "Order #" + event.orderId() + " was cancelled.");
	}

}
