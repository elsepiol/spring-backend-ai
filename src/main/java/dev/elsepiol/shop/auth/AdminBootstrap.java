package dev.elsepiol.shop.auth;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registration only creates normal users, so how does the first ADMIN exist?
 * If BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD are set, this creates that
 * admin at startup (only if the email is not already registered). Nothing is
 * created when they are unset, so production never gets a default admin.
 */
@Component
@ConditionalOnProperty("app.security.bootstrap-admin.email")
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final AuthService authService;
	private final UserRepository users;
	private final String email;
	private final String password;

	public AdminBootstrap(AuthService authService, UserRepository users,
			@Value("${app.security.bootstrap-admin.email}") String email,
			@Value("${app.security.bootstrap-admin.password:}") String password) {
		this.authService = authService;
		this.users = users;
		this.email = email;
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (email.isBlank() || password.isBlank()) {
			return;
		}
		if (users.existsByEmail(email.trim().toLowerCase(Locale.ROOT))) {
			return;
		}
		authService.createUser(email, password, Role.ADMIN);
		log.info("Created bootstrap admin {}", email);
	}

}
