package dev.elsepiol.shop.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole flow with REAL tokens and a REAL database: register, log in, call
 * protected endpoints with the token. No fake authentication anywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest extends PostgresIntegrationTest {

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private void register(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isCreated());
	}

	private String login(String email, String password) throws Exception {
		String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String productJson() {
		return "{\"sku\":\"AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
				+ "\",\"name\":\"Thing\",\"priceCents\":100}";
	}

	@Test
	void registerCreatesAUserAndNeverReturnsTheHash() throws Exception {
		String email = uniqueEmail();

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	@Test
	void theStoredPasswordIsAHash() throws Exception {
		String email = uniqueEmail();
		register(email);

		String stored = users.findByEmail(email).orElseThrow().getPasswordHash();

		org.assertj.core.api.Assertions.assertThat(stored).isNotEqualTo(PASSWORD).startsWith("$2");
	}

	@Test
	void registeringTheSameEmailAgainIs409EvenWithDifferentCase() throws Exception {
		String email = uniqueEmail();
		register(email);

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void weakOrInvalidRegistrationIs400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.email").exists())
				.andExpect(jsonPath("$.errors.password").exists());
	}

	@Test
	void wrongPasswordAndUnknownEmailGiveTheIdenticalResponse() throws Exception {
		String email = uniqueEmail();
		register(email);

		String wrongPassword = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"not the password\"}"))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();
		String unknownEmail = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + uniqueEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		// Identical apart from per-request ids: a trace id (Level 6) differs between ANY two requests
		// and tells an attacker nothing about the email.
		org.assertj.core.api.Assertions.assertThat(withoutTraceId(wrongPassword)).isEqualTo(withoutTraceId(unknownEmail));
	}

	private static String withoutTraceId(String json) {
		return json.replaceAll(",?\"traceId\":\"[0-9a-f]*\"", "");
	}

	@Test
	void anOrdinaryUserWithARealTokenCannotCreateProducts() throws Exception {
		String email = uniqueEmail();
		register(email);
		String token = login(email, PASSWORD);

		mockMvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(productJson()))
				.andExpect(status().isForbidden());
	}

	@Test
	void anAdminWithARealTokenCanCreateProducts() throws Exception {
		String email = uniqueEmail();
		users.saveAndFlush(new User(email, encoder.encode(PASSWORD), Role.ADMIN));
		String token = login(email, PASSWORD);

		mockMvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(productJson()))
				.andExpect(status().isCreated());
	}

	@Test
	void aTamperedTokenIs401() throws Exception {
		String email = uniqueEmail();
		register(email);
		String token = login(email, PASSWORD);
		String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");

		mockMvc.perform(post("/api/v1/products").header("Authorization", "Bearer " + tampered)
				.contentType(MediaType.APPLICATION_JSON).content(productJson()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void browsingProductsNeedsNoToken() throws Exception {
		mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());
	}

}
