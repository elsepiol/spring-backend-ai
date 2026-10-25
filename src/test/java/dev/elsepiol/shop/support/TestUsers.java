package dev.elsepiol.shop.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * Fake logged-in callers for MockMvc requests: .with(TestUsers.admin()).
 * This skips real tokens and signatures and just says "this request is
 * authenticated as someone with this role", which is all most tests need.
 */
public final class TestUsers {

	private TestUsers() {
	}

	public static JwtRequestPostProcessor admin() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	public static JwtRequestPostProcessor user() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

}
