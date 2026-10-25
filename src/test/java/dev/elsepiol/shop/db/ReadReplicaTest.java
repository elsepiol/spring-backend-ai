package dev.elsepiol.shop.db;

import static dev.elsepiol.shop.support.TestUsers.admin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.elsepiol.shop.support.ApiClient;
import dev.elsepiol.shop.support.ApiClient.Account;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import dev.elsepiol.shop.support.PostgresReplica;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The app with a REAL streaming replica (PostgresReplica). The trick that makes these tests
 * reliable: pause the replica's replay, so it is guaranteed to be behind, then check which reads
 * may show the old state and which must not.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadReplicaTest extends PostgresIntegrationTest {

	@DynamicPropertySource
	static void replica(DynamicPropertyRegistry registry) {
		registry.add("app.datasource.replica.url", PostgresReplica::jdbcUrl);
		registry.add("app.datasource.replica.username", PostgresReplica::username);
		registry.add("app.datasource.replica.password", PostgresReplica::password);
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private ApiClient api;

	@BeforeEach
	void setUp() {
		api = new ApiClient(mvc);
	}

	@AfterEach
	void replicaCatchesUp() {
		PostgresReplica.resumeReplay();
	}

	private Boolean onTheReplica(boolean readOnly) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.setReadOnly(readOnly);
		// pg_is_in_recovery() is true on a standby (replica), false on the primary.
		return tx.execute(status -> jdbc.queryForObject("select pg_is_in_recovery()", Boolean.class));
	}

	@Test
	void readOnlyTransactionsRunOnTheReplicaAndEverythingElseOnThePrimary() {
		assertThat(onTheReplica(true)).as("readOnly = true").isTrue();
		assertThat(onTheReplica(false)).as("read-write").isFalse();
	}

	@Test
	void bothPoolsShowUpInTheMetrics() throws Exception {
		// HikariCP opens a pool, and registers its metrics, on first use.
		onTheReplica(true);
		onTheReplica(false);

		mvc.perform(get("/actuator/metrics/hikaricp.connections.max").param("tag", "pool:shop-db-replica").with(admin()))
				.andExpect(status().isOk());
		mvc.perform(get("/actuator/metrics/hikaricp.connections.max").param("tag", "pool:shop-db").with(admin()))
				.andExpect(status().isOk());
	}

	/** THE PRICE OF REPLICAS: a read-only listing can miss what was written a moment ago. PASSES by showing it. */
	@Test
	void aCatalogListingFromTheReplicaCanBeBehind() throws Exception {
		PostgresReplica.pauseReplay();
		long product = api.newProduct(100); // committed on the primary

		mvc.perform(get("/api/v1/products").param("sort", "id,desc").param("size", "1"))
				.andExpect(jsonPath("$.items[0].id").value(org.hamcrest.Matchers.not((int) product)));

		PostgresReplica.resumeReplay();
		mvc.perform(get("/api/v1/products").param("sort", "id,desc").param("size", "1"))
				.andExpect(jsonPath("$.items[0].id").value((int) product));
	}

	@Test
	void someoneWhoJustRegisteredCanLogInAtOnce() throws Exception {
		PostgresReplica.pauseReplay();

		Account fresh = api.newUser(); // registers, then logs in immediately (the login must hit the primary)

		assertThat(fresh.token()).isNotBlank();
	}

	@Test
	void myOwnNewOrderIsAlwaysThereEvenWhileTheReplicaLags() throws Exception {
		Account alice = api.newUser();
		long mug = api.newProductWithStock(100, 10);
		PostgresReplica.resumeReplay(); // the setup has reached the replica
		PostgresReplica.pauseReplay();

		String key = ApiClient.newKey();
		String body = api.placeOrder(alice, key, mug, 1).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long orderId = ((Number) JsonPath.read(body, "$.id")).longValue();

		mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", alice.bearer())).andExpect(status().isOk());
		mvc.perform(get("/api/v1/orders").header("Authorization", alice.bearer()))
				.andExpect(jsonPath("$.items[0].id").value(orderId));
		// A retry must find the original order. Looking on a lagging replica it would not, and then
		// it would crash into the UNIQUE constraint instead of replaying the answer.
		api.placeOrder(alice, key, mug, 1).andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true"));
	}

	/**
	 * Cache + replica, the classic trap: the update evicts the cached product; if the next miss
	 * were read from the lagging replica, the OLD price would go back into the cache for 10 minutes.
	 */
	@Test
	void afterAnUpdateTheCacheIsRefilledFromThePrimaryNotFromALaggingReplica() throws Exception {
		long product = api.newProduct(100);
		mvc.perform(get("/api/v1/products/" + product)).andExpect(jsonPath("$.priceCents").value(100)); // cached
		PostgresReplica.resumeReplay();
		PostgresReplica.pauseReplay();

		mvc.perform(put("/api/v1/products/" + product).with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Thing\",\"priceCents\":250}")).andExpect(status().isOk());

		mvc.perform(get("/api/v1/products/" + product)).andExpect(jsonPath("$.priceCents").value(250));
	}

	@Test
	void writesNeverGoToTheReplica() throws Exception {
		PostgresReplica.pauseReplay();

		// Any write: if it reached the read-only replica it would fail with
		// "cannot execute INSERT in a read-only transaction".
		mvc.perform(post("/api/v1/products").with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"sku\":\"RR-" + System.nanoTime() + "\",\"name\":\"Thing\",\"priceCents\":1}"))
				.andExpect(status().isCreated());
		assertThat(onTheReplica(false)).isFalse();
	}

}
