package dev.elsepiol.shop.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	List<Notification> findTop50ByUserIdOrderByIdDesc(long userId);

	/**
	 * Idempotent insert: the second time the same outbox event is handled, the UNIQUE (event_id)
	 * constraint turns the insert into a no-op instead of a duplicate message. Returns 1 if a row
	 * was written, 0 if this event had already been handled.
	 */
	@Modifying
	@Query(value = "insert into notifications (user_id, event_id, message) values (:userId, :eventId, :message) "
			+ "on conflict (event_id) do nothing", nativeQuery = true)
	int recordOnce(@Param("eventId") long eventId, @Param("userId") long userId, @Param("message") String message);

}
