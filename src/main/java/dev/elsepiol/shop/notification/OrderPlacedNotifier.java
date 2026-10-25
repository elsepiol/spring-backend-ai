package dev.elsepiol.shop.notification;

import dev.elsepiol.shop.order.OrderEvent;
import dev.elsepiol.shop.outbox.OutboxHandler;
import dev.elsepiol.shop.outbox.OutboxMessage;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Your order was placed." In a real shop this is where the confirmation email is sent. It runs
 * in the background, after the order has committed, so a slow or failing mail server can never
 * slow down or fail the purchase itself; the outbox retries it instead.
 */
@Component
public class OrderPlacedNotifier implements OutboxHandler {

	private final NotificationService notifications;
	private final JsonMapper json;

	public OrderPlacedNotifier(NotificationService notifications, JsonMapper json) {
		this.notifications = notifications;
		this.json = json;
	}

	@Override
	public String type() {
		return OrderEvent.PLACED;
	}

	@Override
	public void handle(OutboxMessage message) {
		OrderEvent event = json.readValue(message.payload(), OrderEvent.class);
		String total = BigDecimal.valueOf(event.totalCents(), 2).toPlainString();
		// Keyed by the outbox event id: delivering this event again writes nothing new.
		notifications.recordOnce(message.id(), event.userId(),
				"Order #" + event.orderId() + " was placed. Total: " + total);
	}

}
