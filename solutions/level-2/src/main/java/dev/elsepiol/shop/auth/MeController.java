package dev.elsepiol.shop.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Answers "who am I?" purely from the validated token: no database call. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

	@GetMapping
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		return new MeResponse(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("email"),
				jwt.getClaimAsString("role"));
	}

	public record MeResponse(long id, String email, String role) {
	}

}
