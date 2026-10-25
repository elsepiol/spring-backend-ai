package dev.elsepiol.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.elsepiol.shop.security.IssuedToken;
import dev.elsepiol.shop.security.JwtService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private UserRepository users;

	@Mock
	private JwtService jwtService;

	// A real bcrypt encoder at the lowest cost (4) so tests stay fast; wrapped in a spy to count calls.
	private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));

	private AuthService service;

	@BeforeEach
	void setUp() {
		service = new AuthService(users, encoder, jwtService, Clock.systemUTC());
	}

	@Test
	void registerStoresAHashNotThePasswordAndLowercasesTheEmail() {
		when(users.existsByEmail("ada@example.com")).thenReturn(false);
		when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

		UserResponse response = service.register(new RegisterRequest("  Ada@Example.COM ", "correct horse battery"));

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(users).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getEmail()).isEqualTo("ada@example.com");
		assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("correct horse battery").startsWith("$2");
		assertThat(encoder.matches("correct horse battery", saved.getValue().getPasswordHash())).isTrue();
		assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
		assertThat(response.role()).isEqualTo("USER");
	}

	@Test
	void registerRejectsAnExistingEmail() {
		when(users.existsByEmail("ada@example.com")).thenReturn(true);

		assertThatThrownBy(() -> service.register(new RegisterRequest("ada@example.com", "correct horse battery")))
				.isInstanceOf(DuplicateEmailException.class);
		verify(users, never()).saveAndFlush(any());
	}

	@Test
	void registerTurnsALostRaceIntoTheSameError() {
		when(users.existsByEmail(anyString())).thenReturn(false);
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uq_users_email"));

		assertThatThrownBy(() -> service.register(new RegisterRequest("ada@example.com", "correct horse battery")))
				.isInstanceOf(DuplicateEmailException.class);
	}

	@Test
	void registerRejectsAPasswordLongerThan72Bytes() {
		// 40 characters but 80 bytes in UTF-8: passes the character-count check, must fail the byte check.
		String longInBytes = "é".repeat(40);

		assertThatThrownBy(() -> service.register(new RegisterRequest("ada@example.com", longInBytes)))
				.isInstanceOf(InvalidPasswordException.class);
	}

	@Test
	void loginReturnsATokenForTheRightPassword() {
		User user = new User("ada@example.com", encoder.encode("correct horse battery"), Role.USER);
		when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
		when(jwtService.issue(user)).thenReturn(new IssuedToken("the.jwt.value", Instant.now().plusSeconds(900)));

		TokenResponse response = service.login(new LoginRequest("ADA@example.com", "correct horse battery"));

		assertThat(response.accessToken()).isEqualTo("the.jwt.value");
		assertThat(response.tokenType()).isEqualTo("Bearer");
		assertThat(response.expiresInSeconds()).isBetween(890L, 900L);
	}

	@Test
	void loginRejectsAWrongPassword() {
		User user = new User("ada@example.com", encoder.encode("correct horse battery"), Role.USER);
		when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> service.login(new LoginRequest("ada@example.com", "wrong password!!")))
				.isInstanceOf(InvalidCredentialsException.class);
		verify(jwtService, never()).issue(any());
	}

	@Test
	void loginForAnUnknownEmailFailsTheSameWayAndStillDoesThePasswordWork() {
		when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "whatever password")))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage("Invalid email or password");

		// Timing defence: a hash comparison happened even though there is no such user.
		verify(encoder).matches(any(), any());
	}

}
