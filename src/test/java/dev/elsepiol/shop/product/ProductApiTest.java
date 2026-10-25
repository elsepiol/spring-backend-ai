package dev.elsepiol.shop.product;

import static dev.elsepiol.shop.support.TestUsers.admin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.elsepiol.shop.support.Concurrently;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End to end through every layer: HTTP -> controller -> service -> repository ->
 * real PostgreSQL (with Flyway-created tables) and back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductApiTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	private void create(String sku, String name) throws Exception {
		String body = "{\"sku\":\"" + sku + "\",\"name\":\"" + name + "\",\"priceCents\":1000}";
		mockMvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());
	}

	/** Creates a product with a random SKU and returns its id. */
	private long createPriced(long priceCents) throws Exception {
		String sku = "API-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
		String body = mockMvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"sku\":\"" + sku + "\",\"name\":\"Thing\",\"priceCents\":" + priceCents + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private String etagOf(long id) throws Exception {
		return mockMvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk())
				.andReturn().getResponse().getHeader(HttpHeaders.ETAG);
	}

	private int putPrice(long id, long priceCents, String ifMatch) throws Exception {
		var request = put("/api/v1/products/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Thing\",\"priceCents\":" + priceCents + "}");
		if (ifMatch != null) {
			request = request.header(HttpHeaders.IF_MATCH, ifMatch);
		}
		return mockMvc.perform(request).andReturn().getResponse().getStatus();
	}

	/**
	 * Prices far above anything other tests create, so a scroll that starts just below this band
	 * sees (almost) only this test's products in the shared database.
	 */
	private static long priceBand() {
		return 8_000_000_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L) * 1_000;
	}

	/** Follows nextCursor from the given cursor to the end; returns every id seen, in order. */
	private List<Long> scrollAll(String cursor, int size) throws Exception {
		List<Long> ids = new ArrayList<>();
		while (cursor != null) {
			String body = mockMvc.perform(get("/api/v1/products/scroll").param("cursor", cursor)
					.param("size", String.valueOf(size)))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			List<Number> page = JsonPath.read(body, "$.items[*].id");
			assertThat(page.size()).isLessThanOrEqualTo(size);
			page.forEach(n -> ids.add(n.longValue()));
			cursor = JsonPath.read(body, "$.nextCursor");
		}
		return ids;
	}

	@Test
	void createThenFetchById() throws Exception {
		String body = "{\"sku\":\"API-001\",\"name\":\"Kettle\",\"description\":\"1L\",\"priceCents\":259900}";

		String location = mockMvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getHeader("Location");

		mockMvc.perform(get(location))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sku").value("API-001"))
				.andExpect(jsonPath("$.priceCents").value(259900));
	}

	@Test
	void duplicateSkuReturns409() throws Exception {
		create("API-DUP", "First");

		mockMvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"sku\":\"API-DUP\",\"name\":\"Second\",\"priceCents\":1}"))
				.andExpect(status().isConflict());
	}

	@Test
	void unknownIdReturns404() throws Exception {
		mockMvc.perform(get("/api/v1/products/999999")).andExpect(status().isNotFound());
	}

	@Test
	void listIsPaged() throws Exception {
		create("API-PAGE-1", "P1");
		create("API-PAGE-2", "P2");
		create("API-PAGE-3", "P3");

		mockMvc.perform(get("/api/v1/products?page=0&size=2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
	}

	// ---- Level 4: ETags and conditional requests ----

	@Test
	void anUpdateGivesTheProductANewETag() throws Exception {
		long id = createPriced(1000);
		assertThat(etagOf(id)).isEqualTo("\"0\"");

		assertThat(putPrice(id, 1500, "\"0\"")).isEqualTo(200);

		assertThat(etagOf(id)).isEqualTo("\"1\"");
		mockMvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.priceCents").value(1500));
	}

	@Test
	void anUpdateBasedOnAnOldCopyIs412AndChangesNothing() throws Exception {
		long id = createPriced(1000);
		String seenByAlice = etagOf(id);
		String seenByBob = etagOf(id);

		assertThat(putPrice(id, 1100, seenByAlice)).isEqualTo(200);
		assertThat(putPrice(id, 900, seenByBob)).as("Bob's copy is out of date").isEqualTo(412);

		mockMvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.priceCents").value(1100));
	}

	@Test
	void aConditionalGetIs304WithoutABodyUntilTheProductChanges() throws Exception {
		long id = createPriced(1000);
		String etag = etagOf(id);

		mockMvc.perform(get("/api/v1/products/" + id).header(HttpHeaders.IF_NONE_MATCH, etag))
				.andExpect(status().isNotModified())
				.andExpect(header().string(HttpHeaders.ETAG, etag))
				.andExpect(content().string(""));

		putPrice(id, 2000, null);

		mockMvc.perform(get("/api/v1/products/" + id).header(HttpHeaders.IF_NONE_MATCH, etag))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.priceCents").value(2000));
	}

	/**
	 * Ten admins save at the same instant, none of them sending If-Match. @Version still makes
	 * each saved update build on the previous one: every 200 bumped the version exactly once and
	 * every loser got 409. Without @Version all ten would "succeed" and nine changes would be
	 * silently overwritten.
	 */
	@Test
	void simultaneousUpdatesNeverSilentlyOverwriteEachOther() throws Exception {
		long id = createPriced(1000);

		List<Object> statuses = Concurrently.runAtOnce(10, i -> () -> putPrice(id, 2000 + i, null));

		long saved = statuses.stream().filter(s -> s.equals(200)).count();
		assertThat(statuses).containsOnly(200, 409);
		assertThat(saved).isPositive();
		assertThat(etagOf(id)).as("one version per successful update").isEqualTo("\"" + saved + "\"");
	}

	// ---- Level 4: keyset pagination (/scroll) ----

	@Test
	void scrollWalksEveryProductCheapestFirstWithoutGapsOrRepeats() throws Exception {
		long base = priceBand();
		long[] offsets = { 5, 1, 3, 3, 9, 7, 2 }; // two products share a price: id breaks the tie
		List<long[]> created = new ArrayList<>(); // {price, id}
		for (long offset : offsets) {
			created.add(new long[] { base + offset, createPriced(base + offset) });
		}
		created.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
		List<Long> expected = created.stream().map(p -> p[1]).toList();

		List<Long> seen = scrollAll(Cursor.encode(base, 0), 2);

		assertThat(seen).as("no product twice").doesNotHaveDuplicates();
		assertThat(seen.stream().filter(expected::contains).toList()).as("cheapest first, all of them")
				.isEqualTo(expected);
	}

	/**
	 * Offset pagination would show the product at the page boundary twice after a cheaper one is
	 * added. A cursor says "after THIS row", so later pages do not move.
	 */
	@Test
	void aProductAddedWhileScrollingDoesNotShiftTheNextPage() throws Exception {
		long base = priceBand();
		List<Long> ids = new ArrayList<>();
		for (long offset : new long[] { 10, 20, 30, 40, 50, 60 }) {
			ids.add(createPriced(base + offset));
		}
		String firstPage = mockMvc.perform(get("/api/v1/products/scroll").param("cursor", Cursor.encode(base, 0))
				.param("size", "3"))
				.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<Number>>read(firstPage, "$.items[*].id").stream().map(Number::longValue).toList())
				.isEqualTo(ids.subList(0, 3));

		createPriced(base + 15); // lands inside the page we already read

		mockMvc.perform(get("/api/v1/products/scroll").param("cursor", (String) JsonPath.read(firstPage, "$.nextCursor"))
				.param("size", "3"))
				.andExpect(jsonPath("$.items[0].id").value(ids.get(3)))
				.andExpect(jsonPath("$.items[1].id").value(ids.get(4)))
				.andExpect(jsonPath("$.items[2].id").value(ids.get(5)));
	}

	@Test
	void theLastPageHasNoNextCursor() throws Exception {
		long base = priceBand();
		createPriced(base + 1);

		// Above this price there are only the few products of the other scroll tests: far fewer than 100.
		mockMvc.perform(get("/api/v1/products/scroll").param("cursor", Cursor.encode(base, 0)).param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void aCursorWeDidNotMakeIs400() throws Exception {
		mockMvc.perform(get("/api/v1/products/scroll").param("cursor", "not a cursor"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(get("/api/v1/products/scroll").param("cursor", Cursor.encode(1, 2, 3)))
				.andExpect(status().isBadRequest());
	}

}
