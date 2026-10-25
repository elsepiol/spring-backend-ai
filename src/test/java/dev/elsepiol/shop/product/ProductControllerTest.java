package dev.elsepiol.shop.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static dev.elsepiol.shop.support.TestUsers.admin;

import dev.elsepiol.shop.support.WebSliceSecurityConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web layer only. The service is a mock, so no database is involved. */
@WebMvcTest(ProductController.class)
@Import(WebSliceSecurityConfig.class)
class ProductControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProductService service;

	private static final String VALID_BODY = """
			{"sku":"MUG-001","name":"Blue Mug","description":"A mug","priceCents":49900}
			""";

	@Test
	void createReturns201WithLocationHeader() throws Exception {
		given(service.create(any())).willReturn(
				new ProductResponse(7L, "MUG-001", "Blue Mug", "A mug", 49900, Instant.parse("2026-01-01T00:00:00Z")));

		mockMvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/products/7")))
				.andExpect(jsonPath("$.id").value(7))
				.andExpect(jsonPath("$.sku").value("MUG-001"));
	}

	@Test
	void createWithInvalidBodyReturns400ListingTheBadFields() throws Exception {
		String invalid = """
				{"sku":"bad sku!","name":"","priceCents":-5}
				""";

		mockMvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errors.sku").exists())
				.andExpect(jsonPath("$.errors.name").exists())
				.andExpect(jsonPath("$.errors.priceCents").exists());
	}

	@Test
	void createWithDuplicateSkuReturns409() throws Exception {
		given(service.create(any())).willThrow(new DuplicateSkuException("MUG-001"));

		mockMvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Conflict"));
	}

	@Test
	void getUnknownProductReturns404() throws Exception {
		given(service.getById(99L)).willThrow(new ProductNotFoundException(99L));

		mockMvc.perform(get("/api/v1/products/99"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Product 99 not found"));
	}

}
