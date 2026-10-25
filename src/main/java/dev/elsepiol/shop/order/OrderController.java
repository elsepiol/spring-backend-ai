package dev.elsepiol.shop.order;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import dev.elsepiol.shop.product.PageResponse;

/** Not listed in SecurityConfig: the catch-all rule means every call here needs a valid token. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

	private final OrderService service;

	public OrderController(OrderService service) {
		this.service = service;
	}

	/**
	 * Idempotency-Key: a value the CLIENT makes up (a UUID works) once per purchase attempt and
	 * re-sends unchanged when it retries. If a response is lost (timeout, dropped connection),
	 * the client cannot know whether the order was created. Retrying with the same key is then
	 * safe: it returns the original order instead of creating a second one.
	 */
	@PostMapping
	public ResponseEntity<OrderResponse> place(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody CreateOrderRequest request) {
		PlacedOrder result = service.place(CurrentUser.from(jwt).id(), idempotencyKey, request);
		if (result.replayed()) {
			return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.order());
		}
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}")
				.buildAndExpand(result.order().id())
				.toUri();
		return ResponseEntity.created(location).body(result.order());
	}

	@GetMapping("/{id}")
	public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return service.get(id, CurrentUser.from(jwt));
	}

	/** The caller's own orders, newest first. */
	@GetMapping
	public PageResponse<OrderResponse> listMine(@AuthenticationPrincipal Jwt jwt,
			@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
		return PageResponse.from(service.listMine(CurrentUser.from(jwt).id(), pageable));
	}

	@PostMapping("/{id}/cancel")
	public OrderResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return service.cancel(id, CurrentUser.from(jwt));
	}

}
