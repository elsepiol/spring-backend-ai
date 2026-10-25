package dev.elsepiol.shop.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static dev.elsepiol.shop.support.TestUsers.admin;

import dev.elsepiol.shop.support.WebSliceSecurityConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
				new ProductResponse(7L, "MUG-001", "Blue Mug", "A mug", 49900, Instant.parse("2026-01-01T00:00:00Z"), 0));

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

	// ---- Level 4: ETags ----

	private static ProductResponse mugAtVersion(long version) {
		return new ProductResponse(7L, "MUG-001", "Blue Mug", "A mug", 49900, Instant.parse("2026-01-01T00:00:00Z"),
				version);
	}

	private static final String UPDATE_BODY = "{\"name\":\"Blue Mug\",\"description\":\"A mug\",\"priceCents\":45000}";

	@Test
	void getSendsTheVersionAsETagAndAsksClientsToRevalidate() throws Exception {
		given(service.getById(7L)).willReturn(mugAtVersion(3));

		mockMvc.perform(get("/api/v1/products/7"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
				.andExpect(jsonPath("$.version").doesNotExist()); // the ETag header carries it
	}

	@Test
	void getWithTheCurrentETagIs304WithAnEmptyBody() throws Exception {
		given(service.getById(7L)).willReturn(mugAtVersion(3));

		mockMvc.perform(get("/api/v1/products/7").header(HttpHeaders.IF_NONE_MATCH, "\"3\""))
				.andExpect(status().isNotModified())
				.andExpect(content().string(""));
		mockMvc.perform(get("/api/v1/products/7").header(HttpHeaders.IF_NONE_MATCH, "\"2\""))
				.andExpect(status().isOk());
	}

	@Test
	void putPassesTheIfMatchVersionToTheServiceAndReturnsTheNewETag() throws Exception {
		given(service.update(eq(7L), any(), eq(3L))).willReturn(mugAtVersion(4));

		mockMvc.perform(put("/api/v1/products/7").with(admin()).header(HttpHeaders.IF_MATCH, "\"3\"")
				.contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ETAG, "\"4\""));
	}

	@Test
	void putWithoutIfMatchSkipsTheVersionCheck() throws Exception {
		given(service.update(eq(7L), any(), isNull())).willReturn(mugAtVersion(4));

		mockMvc.perform(put("/api/v1/products/7").with(admin()).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
				.andExpect(status().isOk());
		verify(service).update(eq(7L), any(), isNull());
	}

	@Test
	void putWithAnIfMatchThatIsNotOneOfOurETagsIs412() throws Exception {
		// A weak ETag (W/...) never matches in If-Match, and "abc" or an unquoted 3 were never ours.
		for (String foreign : new String[] { "W/\"3\"", "\"abc\"", "3" }) {
			mockMvc.perform(put("/api/v1/products/7").with(admin()).header(HttpHeaders.IF_MATCH, foreign)
					.contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
					.andExpect(status().isPreconditionFailed())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		verifyNoInteractions(service);
	}

	@Test
	void putWhenSomeoneElseChangedTheProductIs412() throws Exception {
		given(service.update(eq(7L), any(), eq(3L))).willThrow(new ProductChangedException(7L));

		mockMvc.perform(put("/api/v1/products/7").with(admin()).header(HttpHeaders.IF_MATCH, "\"3\"")
				.contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.title").value("Precondition Failed"));
	}

	// ---- Level 4: keyset pagination ----

	@Test
	void scrollReturnsItemsAndTheNextCursor() throws Exception {
		given(service.scrollByPrice(null, 20)).willReturn(new CursorPage<>(List.of(mugAtVersion(0)), "abc"));

		mockMvc.perform(get("/api/v1/products/scroll"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].sku").value("MUG-001"))
				.andExpect(jsonPath("$.nextCursor").value("abc"));
	}

	@Test
	void scrollRejectsPageSizesOutsideOneToAHundred() throws Exception {
		mockMvc.perform(get("/api/v1/products/scroll").param("size", "0")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/products/scroll").param("size", "101")).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

}
