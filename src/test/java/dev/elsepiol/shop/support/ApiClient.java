package dev.elsepiol.shop.support;

import static dev.elsepiol.shop.support.TestUsers.admin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.elsepiol.shop.auth.Role;
import dev.elsepiol.shop.auth.User;
import dev.elsepiol.shop.auth.UserRepository;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Small helper for API tests: creates users (with REAL tokens), products and stock, and
 * places orders. Every call uses fresh random values, so tests never collide in the
 * shared test database.
 */
public final class ApiClient {

	public static final String PASSWORD = "correct horse battery";

	public record Account(long id, String email, String token) {
		public String bearer() {
			return "Bearer " + token;
		}
	}

	private final MockMvc mvc;

	public ApiClient(MockMvc mvc) {
		this.mvc = mvc;
	}

	private static String random(int length) {
		return UUID.randomUUID().toString().replace("-", "").substring(0, length);
	}

	/** Registers a normal user through the API and logs in. */
	public Account newUser() throws Exception {
		String email = "u-" + random(10) + "@example.com";
		String body = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();
		return new Account(id, email, login(email));
	}

	/** Inserts an ADMIN directly (the API cannot create one) and logs in. */
	public Account newAdmin(UserRepository users, PasswordEncoder encoder) throws Exception {
		String email = "a-" + random(10) + "@example.com";
		User admin = users.saveAndFlush(new User(email, encoder.encode(PASSWORD), Role.ADMIN));
		return new Account(admin.getId(), email, login(email));
	}

	public String login(String email) throws Exception {
		String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	public long newProduct(long priceCents) throws Exception {
		String body = mvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"sku\":\"T-" + random(12).toUpperCase() + "\",\"name\":\"Thing " + random(6)
						+ "\",\"priceCents\":" + priceCents + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	public long newProductWithStock(long priceCents, int stock) throws Exception {
		long id = newProduct(priceCents);
		setStock(id, stock);
		return id;
	}

	public void setStock(long productId, int quantity) throws Exception {
		mvc.perform(put("/api/v1/products/" + productId + "/stock").with(admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantity\":" + quantity + "}"))
				.andExpect(status().isNoContent());
	}

	public int stockOf(long productId) throws Exception {
		String body = mvc.perform(get("/api/v1/products/" + productId + "/stock"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.quantity");
	}

	/**
	 * Places an order. The varargs are pairs: productId, quantity, productId, quantity...
	 * A null key leaves the Idempotency-Key header out.
	 */
	public ResultActions placeOrder(Account who, String key, long... productAndQuantity) throws Exception {
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < productAndQuantity.length; i += 2) {
			if (i > 0) {
				items.append(',');
			}
			items.append("{\"productId\":").append(productAndQuantity[i]).append(",\"quantity\":")
					.append(productAndQuantity[i + 1]).append('}');
		}
		var request = post("/api/v1/orders").header("Authorization", who.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[" + items + "]}");
		if (key != null) {
			request = request.header("Idempotency-Key", key);
		}
		return mvc.perform(request);
	}

	public static String newKey() {
		return UUID.randomUUID().toString();
	}

}
