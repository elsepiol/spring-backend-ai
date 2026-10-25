package dev.elsepiol.shop.order;

import static dev.elsepiol.shop.support.TestUsers.admin;
import static dev.elsepiol.shop.support.TestUsers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.elsepiol.shop.auth.UserRepository;
import dev.elsepiol.shop.support.ApiClient;
import dev.elsepiol.shop.support.ApiClient.Account;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Orders end to end with REAL tokens: register, log in, buy, retry, cancel. */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private JdbcTemplate jdbc;

	private ApiClient api;

	@BeforeEach
	void setUp() {
		api = new ApiClient(mvc);
	}

	@Test
	void placingAnOrderReturns201AndTakesTheStock() throws Exception {
		Account alice = api.newUser();
		long mug = api.newProductWithStock(1500, 10);
		long plate = api.newProductWithStock(2500, 10);

		api.placeOrder(alice, ApiClient.newKey(), mug, 2, plate, 1)
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.status").value("PLACED"))
				.andExpect(jsonPath("$.totalCents").value(5500))
				.andExpect(jsonPath("$.items.length()").value(2));

		assertThat(api.stockOf(mug)).isEqualTo(8);
		assertThat(api.stockOf(plate)).isEqualTo(9);
	}

	@Test
	void notEnoughStockIs409AndNothingIsChanged() throws Exception {
		Account alice = api.newUser();
		long plenty = api.newProductWithStock(100, 10);
		long scarce = api.newProductWithStock(100, 1);

		api.placeOrder(alice, ApiClient.newKey(), plenty, 5, scarce, 2)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.productId").value(scarce));

		// The whole order is rolled back, including the part for the product that WAS available.
		assertThat(api.stockOf(plenty)).isEqualTo(10);
		assertThat(api.stockOf(scarce)).isEqualTo(1);
		mvc.perform(get("/api/v1/orders").header("Authorization", alice.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void aMissingIdempotencyKeyIs400() throws Exception {
		Account alice = api.newUser();
		long mug = api.newProductWithStock(100, 5);

		api.placeOrder(alice, null, mug, 1).andExpect(status().isBadRequest());
	}

	@Test
	void retryingWithTheSameKeyReturnsTheSameOrderWithoutBuyingAgain() throws Exception {
		Account alice = api.newUser();
		long mug = api.newProductWithStock(100, 5);
		String key = ApiClient.newKey();

		String first = api.placeOrder(alice, key, mug, 1).andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andReturn().getResponse().getContentAsString();
		String second = api.placeOrder(alice, key, mug, 1).andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andReturn().getResponse().getContentAsString();

		assertThat((Number) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
		assertThat(api.stockOf(mug)).as("bought once").isEqualTo(4);
	}

	@Test
	void reusingAKeyForADifferentOrderIs422() throws Exception {
		Account alice = api.newUser();
		long mug = api.newProductWithStock(100, 5);
		String key = ApiClient.newKey();

		api.placeOrder(alice, key, mug, 1).andExpect(status().isCreated());
		api.placeOrder(alice, key, mug, 2).andExpect(status().isUnprocessableEntity());

		assertThat(api.stockOf(mug)).isEqualTo(4);
	}

	@Test
	void theSameKeyFromTwoDifferentUsersAreTwoDifferentOrders() throws Exception {
		Account alice = api.newUser();
		Account bob = api.newUser();
		long mug = api.newProductWithStock(100, 5);
		String key = ApiClient.newKey();

		api.placeOrder(alice, key, mug, 1).andExpect(status().isCreated());
		api.placeOrder(bob, key, mug, 1).andExpect(status().isCreated());

		assertThat(api.stockOf(mug)).isEqualTo(3);
	}

	@Test
	void anUnknownProductIs400() throws Exception {
		Account alice = api.newUser();

		api.placeOrder(alice, ApiClient.newKey(), 987654321L, 1).andExpect(status().isBadRequest());
	}

	@Test
	void invalidQuantitiesAndEmptyOrdersAre400() throws Exception {
		Account alice = api.newUser();
		long mug = api.newProductWithStock(100, 5);

		api.placeOrder(alice, ApiClient.newKey(), mug, 0).andExpect(status().isBadRequest());
		api.placeOrder(alice, ApiClient.newKey(), mug, 101).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/orders").header("Authorization", alice.bearer())
				.header("Idempotency-Key", ApiClient.newKey())
				.contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void duplicateLinesForOneProductAreMerged() throws Exception {
		Account alice = api.newUser();
		long mug = api.newProductWithStock(100, 5);

		api.placeOrder(alice, ApiClient.newKey(), mug, 1, mug, 2)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].quantity").value(3));

		assertThat(api.stockOf(mug)).isEqualTo(2);
	}

	@Test
	void ordersNeedAToken() throws Exception {
		mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anotherUsersOrderLooksLikeItDoesNotExistButAnAdminCanSeeIt() throws Exception {
		Account alice = api.newUser();
		Account mallory = api.newUser();
		Account boss = api.newAdmin(users, encoder);
		long mug = api.newProductWithStock(100, 5);
		String body = api.placeOrder(alice, ApiClient.newKey(), mug, 1).andReturn().getResponse().getContentAsString();
		String path = "/api/v1/orders/" + ((Number) JsonPath.read(body, "$.id")).longValue();

		mvc.perform(get(path).header("Authorization", alice.bearer())).andExpect(status().isOk());
		mvc.perform(get(path).header("Authorization", mallory.bearer())).andExpect(status().isNotFound());
		mvc.perform(post(path + "/cancel").header("Authorization", mallory.bearer())).andExpect(status().isNotFound());
		mvc.perform(get(path).header("Authorization", boss.bearer())).andExpect(status().isOk());
		assertThat(api.stockOf(mug)).as("mallory's cancel attempt changed nothing").isEqualTo(4);
	}

	@Test
	void cancellingPutsStockBackAndCannotBeDoneTwice() throws Exception {
		Account alice = api.newUser();
		long mug = api.newProductWithStock(100, 5);
		String body = api.placeOrder(alice, ApiClient.newKey(), mug, 3).andReturn().getResponse().getContentAsString();
		String path = "/api/v1/orders/" + ((Number) JsonPath.read(body, "$.id")).longValue();
		assertThat(api.stockOf(mug)).isEqualTo(2);

		mvc.perform(post(path + "/cancel").header("Authorization", alice.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(api.stockOf(mug)).isEqualTo(5);

		mvc.perform(post(path + "/cancel").header("Authorization", alice.bearer())).andExpect(status().isConflict());
		assertThat(api.stockOf(mug)).as("second cancel must not return stock again").isEqualTo(5);
	}

	@Test
	void listingShowsOnlyMyOrdersNewestFirstAndIsPaged() throws Exception {
		Account alice = api.newUser();
		Account bob = api.newUser();
		long mug = api.newProductWithStock(100, 50);
		long[] ids = new long[3];
		for (int i = 0; i < 3; i++) {
			String body = api.placeOrder(alice, ApiClient.newKey(), mug, 1).andReturn().getResponse().getContentAsString();
			ids[i] = ((Number) JsonPath.read(body, "$.id")).longValue();
		}
		api.placeOrder(bob, ApiClient.newKey(), mug, 1).andExpect(status().isCreated());

		mvc.perform(get("/api/v1/orders").header("Authorization", alice.bearer()).param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].id").value(ids[2]))
				.andExpect(jsonPath("$.items[1].id").value(ids[1]));
	}

	@Test
	void anOrderKeepsTheOldPriceWhenTheProductPriceChanges() throws Exception {
		Account alice = api.newUser();
		long mug = api.newProductWithStock(1000, 5);
		String body = api.placeOrder(alice, ApiClient.newKey(), mug, 2).andReturn().getResponse().getContentAsString();
		String path = "/api/v1/orders/" + ((Number) JsonPath.read(body, "$.id")).longValue();

		jdbc.update("update products set price_cents = 9999 where id = ?", mug);

		mvc.perform(get(path).header("Authorization", alice.bearer()))
				.andExpect(jsonPath("$.totalCents").value(2000))
				.andExpect(jsonPath("$.items[0].unitPriceCents").value(1000));
	}

	// ---- stock endpoints ----

	@Test
	void onlyAdminsCanSetStockButAnyoneCanRead() throws Exception {
		long mug = api.newProduct(100);

		mvc.perform(put("/api/v1/products/" + mug + "/stock").contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantity\":5}")).andExpect(status().isUnauthorized());
		mvc.perform(put("/api/v1/products/" + mug + "/stock").with(user()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantity\":5}")).andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/products/" + mug + "/stock").with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantity\":5}")).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/products/" + mug + "/stock"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.quantity").value(5));
	}

	@Test
	void stockRejectsNegativeNumbersAndUnknownProducts() throws Exception {
		long mug = api.newProduct(100);

		mvc.perform(put("/api/v1/products/" + mug + "/stock").with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantity\":-1}")).andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/products/987654321/stock").with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantity\":1}")).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/products/987654321/stock")).andExpect(status().isNotFound());
	}

	@Test
	void aProductWithNoStockRecordReadsAsZero() throws Exception {
		long mug = api.newProduct(100);

		assertThat(api.stockOf(mug)).isZero();
	}

}
