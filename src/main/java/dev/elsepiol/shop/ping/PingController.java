package dev.elsepiol.shop.ping;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Level 0 feature: the smallest possible HTTP endpoint.
 *
 * Read docs/learn/00-orientation.md to see what each annotation does and how a
 * request travels from the network to this method and back.
 */
@RestController
@RequestMapping("/api/v1/ping")
public class PingController {

	private final String serviceName;

	// Spring reads spring.application.name from application.properties and
	// passes it in when it creates this object (constructor injection).
	public PingController(@Value("${spring.application.name}") String serviceName) {
		this.serviceName = serviceName;
	}

	@GetMapping
	public PingResponse ping() {
		return new PingResponse("ok", serviceName);
	}

	/** The JSON shape of the response. A record is an immutable data carrier. */
	public record PingResponse(String status, String service) {
	}

}
