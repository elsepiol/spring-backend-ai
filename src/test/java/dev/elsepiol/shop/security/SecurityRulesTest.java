package dev.elsepiol.shop.security;

import static dev.elsepiol.shop.support.TestUsers.admin;
import static dev.elsepiol.shop.support.TestUsers.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.elsepiol.shop.product.PageResponse;
import dev.elsepiol.shop.product.ProductController;
import dev.elsepiol.shop.product.ProductResponse;
import dev.elsepiol.shop.product.ProductService;
import dev.elsepiol.shop.support.WebSliceSecurityConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests OUR rules in SecurityConfig, using the product endpoints as the subject.
 * Each test sends a request as a different kind of caller and checks the status.
 */
@WebMvcTest(ProductController.class)
@Import(WebSliceSecurityConfig.class)
class SecurityRulesTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProductService service;

	private static final String BODY = "{\"sku\":\"MUG-001\",\"name\":\"Blue Mug\",\"priceCents\":1}";

	@Test
	void anonymousUsersCanBrowseProducts() throws Exception {
		given(service.list(any())).willReturn(new PageResponse<ProductResponse>(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());
	}

	@Test
	void creatingWithoutATokenIs401WithProblemDetailsAndChallengeHeader() throws Exception {
		mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("WWW-Authenticate"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.title").value("Unauthorized"));
	}

	@Test
	void aGarbageTokenIs401() throws Exception {
		mockMvc.perform(post("/api/v1/products")
				.header("Authorization", "Bearer not.a.real.token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void aNormalUserCannotCreateProducts() throws Exception {
		mockMvc.perform(post("/api/v1/products").with(user()).contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Forbidden"));
	}

	@Test
	void anAdminCanCreateProducts() throws Exception {
		given(service.create(any())).willReturn(new ProductResponse(1L, "MUG-001", "Blue Mug", null, 1, Instant.now()));

		mockMvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isCreated());
	}

	@Test
	void deletingIsAlsoAdminOnly() throws Exception {
		mockMvc.perform(delete("/api/v1/products/1")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/v1/products/1").with(user())).andExpect(status().isForbidden());
	}

	@Test
	void unlistedPathsAreDeniedByDefault() throws Exception {
		// Nothing is mapped here and no rule mentions it: the catch-all rule still protects it.
		mockMvc.perform(get("/api/v1/some-future-endpoint")).andExpect(status().isUnauthorized());
	}

}
