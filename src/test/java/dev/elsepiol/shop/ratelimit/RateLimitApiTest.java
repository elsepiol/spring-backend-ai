package dev.elsepiol.shop.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.elsepiol.shop.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Login and register are limited per client address: 3 per minute here, so the test is short.
 * Every test uses its own made-up address, so tests do not share buckets.
 */
@SpringBootTest
@TestPropertySource(properties = { "app.rate-limit.auth.capacity=3", "app.rate-limit.auth.period=1m" })
@AutoConfigureMockMvc
class RateLimitApiTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mvc;

	private static MockHttpServletRequestBuilder loginFrom(String address, String password) {
		return post("/api/v1/auth/login").with(request -> {
			request.setRemoteAddr(address);
			return request;
		}).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"victim@example.com\",\"password\":\"" + password + "\"}");
	}

	@Test
	void aPasswordGuesserIsStoppedAfterTheLimitWith429AndRetryAfter() throws Exception {
		for (int i = 0; i < 3; i++) {
			mvc.perform(loginFrom("198.51.100.1", "guess number " + i)).andExpect(status().isUnauthorized());
		}

		mvc.perform(loginFrom("198.51.100.1", "guess number 4"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "20")) // 3 per minute: one every 20 s
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(429))
				.andExpect(jsonPath("$.title").value("Too Many Requests"));
	}

	@Test
	void otherAddressesAreNotAffected() throws Exception {
		for (int i = 0; i < 4; i++) {
			mvc.perform(loginFrom("198.51.100.2", "guess " + i));
		}

		mvc.perform(loginFrom("198.51.100.3", "guess")).andExpect(status().isUnauthorized()); // judged, not throttled
	}

	@Test
	void registeringCountsAgainstTheSameBudget() throws Exception {
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/api/v1/auth/register").with(request -> {
				request.setRemoteAddr("198.51.100.4");
				return request;
			}).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\",\"password\":\"x\"}"))
					.andExpect(status().isBadRequest());
		}

		mvc.perform(loginFrom("198.51.100.4", "guess")).andExpect(status().isTooManyRequests());
	}

}
