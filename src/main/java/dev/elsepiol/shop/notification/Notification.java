package dev.elsepiol.shop.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One in-app message for a user. Rows are written by NotificationRepository.recordOnce only. */
@Entity
@Table(name = "notifications")
public class Notification {

	@Id
	private Long id;

	@Column(name = "user_id", nullable = false)
	private long userId;

	@Column(name = "event_id", nullable = false)
	private long eventId;

	@Column(nullable = false)
	private String message;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Notification() {
	}

	public Long getId() {
		return id;
	}

	public long getUserId() {
		return userId;
	}

	public long getEventId() {
		return eventId;
	}

	public String getMessage() {
		return message;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
