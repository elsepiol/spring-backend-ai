package dev.elsepiol.shop.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService service;

	public AuthController(AuthService service) {
		this.service = service;
	}

	@PostMapping("/register")
	public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.register(request));
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return service.login(request);
	}

	// Not listed in SecurityConfig, so the catch-all rule makes it require a valid token.
	// @AuthenticationPrincipal hands us the validated token; "sub" is the user's id.
	@PostMapping("/change-password")
	public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody ChangePasswordRequest request) {
		service.changePassword(Long.parseLong(jwt.getSubject()), request);
		return ResponseEntity.noContent().build();
	}

}
