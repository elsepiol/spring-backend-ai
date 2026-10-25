package dev.elsepiol.shop.notification;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

	private final NotificationRepository repository;

	public NotificationService(NotificationRepository repository) {
		this.repository = repository;
	}

	/** Writes the message once per outbox event, however often the event is delivered. */
	@Transactional
	public boolean recordOnce(long eventId, long userId, String message) {
		return repository.recordOnce(eventId, userId, message) == 1;
	}

	/** The caller's latest 50, newest first. A user's own data: read from the primary (no readOnly). */
	@Transactional
	public List<NotificationResponse> latestFor(long userId) {
		return repository.findTop50ByUserIdOrderByIdDesc(userId).stream().map(NotificationResponse::from).toList();
	}

}
