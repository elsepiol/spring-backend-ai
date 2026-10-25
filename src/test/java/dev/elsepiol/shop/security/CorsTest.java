package dev.elsepiol.shop.security;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.elsepiol.shop.product.ProductController;
import dev.elsepiol.shop.product.ProductResponse;
import dev.elsepiol.shop.product.ProductService;
import dev.elsepiol.shop.support.WebSliceSecurityConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** What a browser on https://shop.example may do with this API, and what a browser elsewhere may not. */
@WebMvcTest(ProductController.class)
@Import(WebSliceSecurityConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=https://shop.example")
class CorsTest {

	private static final String ALLOWED = "https://shop.example";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private ProductService service;

	@Test
	void aPreflightFromTheAllowedOriginIsAnsweredWithoutAToken() throws Exception {
		mvc.perform(options("/api/v1/products").header("Origin", ALLOWED)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
				.andExpect(header().string("Access-Control-Allow-Headers", containsString("idempotency-key")))
				.andExpect(header().string("Access-Control-Max-Age", "3600"));
	}

	@Test
	void anyOtherOriginIsRefused() throws Exception {
		mvc.perform(options("/api/v1/products").header("Origin", "https://evil.example")
				.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void aHeaderWeDoNotAllowFailsThePreflight() throws Exception {
		mvc.perform(options("/api/v1/products").header("Origin", ALLOWED)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "x-made-up"))
				.andExpect(status().isForbidden());
	}

	@Test
	void theFrontendMayReadTheETagRetryAfterAndTraceIdHeaders() throws Exception {
		given(service.getById(7L)).willReturn(
				new ProductResponse(7L, "MUG-001", "Blue Mug", null, 100, Instant.parse("2026-01-01T00:00:00Z"), 2));

		mvc.perform(get("/api/v1/products/7").header("Origin", ALLOWED))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
				.andExpect(header().string("Access-Control-Expose-Headers", containsString("ETag")))
				.andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
				.andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Trace-Id")));
	}

	@Test
	void sameOriginRequestsAreNotAffected() throws Exception {
		given(service.getById(7L)).willReturn(
				new ProductResponse(7L, "MUG-001", "Blue Mug", null, 100, Instant.parse("2026-01-01T00:00:00Z"), 2));

		mvc.perform(get("/api/v1/products/7")) // no Origin header: not a cross-origin request
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

}
