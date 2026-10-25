package dev.elsepiol.shop.product;

import static dev.elsepiol.shop.support.TestUsers.admin;
import static dev.elsepiol.shop.support.TestUsers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.benmanes.caffeine.cache.Policy;
import dev.elsepiol.shop.support.ApiClient;
import dev.elsepiol.shop.support.Concurrently;
import dev.elsepiol.shop.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the product cache does, and what it does not do. Hibernate statistics count the SQL
 * statements and database sessions behind each request, so "served from the cache" is measured,
 * not assumed. (Same configuration as OrderQueryCountTest, so both share one application context.)
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class ProductCacheTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private CacheManager cacheManager;

	@Autowired
	private JdbcTemplate jdbc;

	private ApiClient api;

	private Statistics statistics;

	@BeforeEach
	void setUp() {
		api = new ApiClient(mvc);
		statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	}

	private void expectPrice(long id, long priceCents) throws Exception {
		mvc.perform(get("/api/v1/products/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.priceCents").value(priceCents));
	}

	@Test
	void aSecondReadIsServedFromTheCacheWithoutTouchingTheDatabase() throws Exception {
		long id = api.newProduct(500);
		expectPrice(id, 500); // miss: runs the query and fills the cache
		statistics.clear();

		expectPrice(id, 500); // hit

		assertThat(statistics.getPrepareStatementCount()).as("SQL statements").isZero();
		// Not even a database session was opened, so no pool connection was borrowed. This holds
		// only because the cache proxy sits outside the transaction proxy (see CacheConfig).
		assertThat(statistics.getSessionOpenCount()).as("database sessions").isZero();
	}

	@Test
	void updatingAProductEvictsItSoTheNextReadIsFresh() throws Exception {
		long id = api.newProduct(500);
		expectPrice(id, 500); // now cached

		mvc.perform(put("/api/v1/products/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Renamed\",\"priceCents\":777}"))
				.andExpect(status().isOk());

		expectPrice(id, 777);
	}

	/**
	 * THE PRICE OF CACHING. A change that does not go through ProductService (a manual SQL fix,
	 * a batch job, or an update served by ANOTHER instance of this app, whose eviction only
	 * clears its own memory) is invisible until the entry expires or is evicted. This test
	 * PASSES by showing the stale read. expireAfterWrite=10m bounds how long it can last.
	 */
	@Test
	void aChangeMadeBehindTheAppsBackIsNotSeenUntilTheEntryIsEvicted() throws Exception {
		long id = api.newProduct(500);
		expectPrice(id, 500);

		jdbc.update("update products set price_cents = 999 where id = ?", id);

		expectPrice(id, 500); // stale: still the cached copy
		cacheManager.getCache(ProductService.CACHE).evict(id);
		expectPrice(id, 999);
	}

	/** @Cacheable(sync = true): a burst of misses for one key becomes ONE database query. */
	@Test
	void twentySimultaneousMissesForOneProductRunOneQuery() throws Exception {
		long id = api.newProduct(500); // created, never read: not in the cache yet
		statistics.clear();

		List<Object> statuses = Concurrently.runAtOnce(20,
				i -> () -> mvc.perform(get("/api/v1/products/" + id)).andReturn().getResponse().getStatus());

		assertThat(statuses).as("all answered").containsOnly(200);
		assertThat(statistics.getPrepareStatementCount()).as("SQL statements for 20 requests").isEqualTo(1);
	}

	/** An unbounded cache is a memory leak with extra steps. */
	@Test
	void theCacheIsBoundedAndEntriesExpire() {
		var nativeCache = ((CaffeineCache) cacheManager.getCache(ProductService.CACHE)).getNativeCache();
		Policy<Object, Object> policy = nativeCache.policy();

		assertThat(policy.eviction().orElseThrow().getMaximum()).isEqualTo(10_000);
		assertThat(policy.expireAfterWrite().orElseThrow().getExpiresAfter()).isEqualTo(Duration.ofMinutes(10));
	}

	@Test
	void hitsAndMissesShowUpInTheMetricsWhichOnlyAdminsMaySee() throws Exception {
		long id = api.newProduct(500);
		expectPrice(id, 500);
		expectPrice(id, 500);

		mvc.perform(get("/actuator/metrics/cache.gets").param("tag", "cache:products").param("tag", "result:hit")
				.with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.measurements[0].value").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1.0)));
		mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/metrics").with(user())).andExpect(status().isForbidden());
	}

}
