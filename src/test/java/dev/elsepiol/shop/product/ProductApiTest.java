package dev.elsepiol.shop.product;

import static dev.elsepiol.shop.support.TestUsers.admin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.elsepiol.shop.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

}
