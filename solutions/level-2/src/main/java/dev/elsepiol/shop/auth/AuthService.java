package dev.elsepiol.shop.auth;

import dev.elsepiol.shop.security.IssuedToken;
import dev.elsepiol.shop.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final Clock clock;

	/**
	 * A hash of a throwaway password. When the email is unknown we still run one
	 * password check against this, so "unknown email" takes as long as "wrong
	 * password". Otherwise response time would reveal which emails have accounts.
	 */
	private final String dummyHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode("not-a-real-password");
	}

	@Transactional
	public UserResponse register(RegisterRequest request) {
		return UserResponse.from(createUser(request.email(), request.password(), Role.USER));
	}

	/** Used by register and by the startup admin bootstrap. */
	@Transactional
	public User createUser(String email, String rawPassword, Role role) {
		String normalized = normalize(email);
		requireStorablePassword(rawPassword);
		if (users.existsByEmail(normalized)) {
			throw new DuplicateEmailException();
		}
		try {
			return users.saveAndFlush(new User(normalized, passwordEncoder.encode(rawPassword), role));
		}
		catch (DataIntegrityViolationException ex) {
			throw new DuplicateEmailException(); // lost a race; the UNIQUE constraint caught it
		}
	}

	// Not read-only (Level 5): read-only work may go to a read replica, which lags behind. Someone
	// who registered a second ago must be able to log in, and after a password change the OLD
	// password must stop working at once, not when the replica catches up.
	@Transactional
	public TokenResponse login(LoginRequest request) {
		User user = users.findByEmail(normalize(request.email())).orElse(null);

		String hashToCheck = (user != null) ? user.getPasswordHash() : dummyHash;
		boolean matches = passwordEncoder.matches(request.password(), hashToCheck);

		if (user == null || !matches) {
			throw new InvalidCredentialsException();
		}

		IssuedToken token = jwtService.issue(user);
		long seconds = Duration.between(clock.instant(), token.expiresAt()).getSeconds();
		return new TokenResponse(token.value(), "Bearer", seconds);
	}

	private static String normalize(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	/** bcrypt reads only the first 72 bytes; refuse longer input instead of silently truncating it. */
	private static void requireStorablePassword(String rawPassword) {
		if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new InvalidPasswordException("Password must be at most 72 bytes");
		}
	}

	/**
	 * No save() call needed: the user is a managed entity inside this transaction, so
	 * Hibernate notices the changed field ("dirty checking") and writes the UPDATE at commit.
	 * Note the old token stays valid until it expires; see the lesson on revocation.
	 */
	@Transactional
	public void changePassword(long userId, ChangePasswordRequest request) {
		User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
		if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
			throw new InvalidPasswordException("Current password is incorrect");
		}
		requireStorablePassword(request.newPassword());
		user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
	}

}
