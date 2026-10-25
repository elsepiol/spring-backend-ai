package dev.elsepiol.shop.notification;

import java.time.Instant;

public record NotificationResponse(long id, String message, Instant createdAt) {

	static NotificationResponse from(Notification n) {
		return new NotificationResponse(n.getId(), n.getMessage(), n.getCreatedAt());
	}

}
