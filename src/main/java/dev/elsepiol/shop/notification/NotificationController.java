package dev.elsepiol.shop.notification;

import dev.elsepiol.shop.order.CurrentUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Not listed in SecurityConfig: the catch-all rule means a valid token is required.
 * Messages appear a moment AFTER the action that causes them: they are written by the outbox
 * worker, not by the request (eventual consistency).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

	private final NotificationService service;

	public NotificationController(NotificationService service) {
		this.service = service;
	}

	@GetMapping
	public List<NotificationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
		return service.latestFor(CurrentUser.from(jwt).id());
	}

}
