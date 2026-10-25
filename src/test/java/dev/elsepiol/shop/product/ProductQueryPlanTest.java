package dev.elsepiol.shop.product;

import static org.assertj.core.api.Assertions.assertThat;

import dev.elsepiol.shop.support.PostgresIntegrationTest;
import dev.elsepiol.shop.support.QueryPlan;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves with EXPLAIN that the queries behind pagination use the indexes we think they use.
 *
 * On a table of a few rows PostgreSQL rightly ignores every index (reading one page is cheaper
 * than using an index), so each test first inserts 20,000 products and runs ANALYZE so the
 * planner knows the table is big. All of it happens inside a transaction that is rolled back,
 * so the shared test database is left as it was.
 */
@SpringBootTest
class ProductQueryPlanTest extends PostgresIntegrationTest {

	@Autowired
	private NamedParameterJdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	/** Runs the work against a table of 20,000 extra products, then throws them all away. */
	private <T> T withManyProducts(Supplier<T> work) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		return tx.execute(status -> {
			status.setRollbackOnly();
			jdbc.update("""
					insert into products (sku, name, price_cents)
					select 'QP-' || g || '-' || :token, 'Plan test ' || g, (g * 7919) % 100000
					from generate_series(1, 20000) g""", Map.of("token", UUID.randomUUID().toString().substring(0, 8)));
			jdbc.getJdbcTemplate().execute("analyze products");
			return work.get();
		});
	}

	@Test
	void scrollingByPriceJumpsStraightToTheCursorInTheIndexAndNeverSorts() {
		QueryPlan plan = withManyProducts(() -> QueryPlan.explain(jdbc, ProductRepository.SCROLL_BY_PRICE_SQL,
				Map.of("price", 50_000L, "id", 0L, "limit", 21), true));

		assertThat(plan.indexNames()).as(plan.toString()).containsExactly("idx_products_price_id");
		assertThat(plan.nodeTypes()).as(plan.toString()).doesNotContain("Seq Scan", "Sort");
		// The cursor position is an INDEX condition: PostgreSQL seeks to it instead of reading
		// and discarding everything before it.
		assertThat(plan.indexConditions()).as(plan.toString()).singleElement().asString()
				.contains("price_cents", "id");
		assertThat(plan.rowsRemovedByFilter()).as(plan.toString()).isZero();
	}

	/**
	 * The same question written with OR. Same rows, same index available, but PostgreSQL cannot
	 * turn the OR into a starting position in the index, so it reads the index from the cheapest
	 * product and throws away every row before the cursor. This test PASSES by showing that waste.
	 */
	@Test
	void theSameKeysetQueryWrittenWithOrReadsEverythingBeforeTheCursor() {
		String withOr = """
				select * from products
				where price_cents > :price or (price_cents = :price and id > :id)
				order by price_cents, id
				limit :limit""";

		QueryPlan plan = withManyProducts(() -> QueryPlan.explain(jdbc, withOr,
				Map.of("price", 50_000L, "id", 0L, "limit", 21), true));

		assertThat(plan.rowsRemovedByFilter()).as(plan.toString()).isGreaterThan(5_000);
	}

	/**
	 * Offset pagination: to return rows 10,001 to 10,020, PostgreSQL produces the first 10,020
	 * rows and drops 10,000 of them. Page 1 is cheap, page 500 is not. This test PASSES by
	 * showing that cost; it is why /scroll exists.
	 */
	@Test
	void offsetPaginationReadsEveryRowItSkips() {
		QueryPlan plan = withManyProducts(() -> QueryPlan.explain(jdbc,
				"select * from products order by id offset :offset limit :limit",
				Map.of("offset", 10_000, "limit", 20), true));

		// actualRows() is top first: [Limit, the scan below it]
		assertThat(plan.actualRows().get(0)).as(plan.toString()).isEqualTo(20);
		assertThat(plan.actualRows().get(1)).as(plan.toString()).isGreaterThanOrEqualTo(10_020);
	}

}
