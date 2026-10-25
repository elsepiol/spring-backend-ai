package dev.elsepiol.shop.support;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Asks PostgreSQL how it will run a query: EXPLAIN. The plan is a tree of nodes ("Limit",
 * "Index Scan", "Sort", "Seq Scan"...). We read the JSON form to make assertions and keep the
 * text form (what you would see in psql) for failure messages.
 *
 * With analyze = true the query really runs (EXPLAIN ANALYZE), and every node also reports how
 * many rows it actually produced. Use that inside a transaction you roll back.
 */
public final class QueryPlan {

	private final String json;
	private final String text;

	private QueryPlan(String json, String text) {
		this.json = json;
		this.text = text;
	}

	public static QueryPlan explain(NamedParameterJdbcTemplate jdbc, String sql, Map<String, ?> params,
			boolean analyze) {
		String options = analyze ? "ANALYZE, " : "";
		String json = jdbc.queryForObject("EXPLAIN (" + options + "FORMAT JSON) " + sql, params, String.class);
		String text = String.join("\n", jdbc.queryForList("EXPLAIN (" + options + "FORMAT TEXT) " + sql, params,
				String.class));
		return new QueryPlan(json, text);
	}

	/** Every node in the tree, top first: e.g. [Limit, Index Scan]. */
	public List<String> nodeTypes() {
		return JsonPath.read(json, "$..['Node Type']");
	}

	/** The indexes the plan uses. */
	public List<String> indexNames() {
		return JsonPath.read(json, "$..['Index Name']");
	}

	/** Conditions checked INSIDE the index (the part an index can jump to), one per index node. */
	public List<String> indexConditions() {
		return JsonPath.read(json, "$..['Index Cond']");
	}

	/** EXPLAIN ANALYZE only: rows each node actually produced, top first. */
	public List<Integer> actualRows() {
		return JsonPath.read(json, "$..['Actual Rows']");
	}

	/** EXPLAIN ANALYZE only: rows a node read and then threw away because they did not match. */
	public long rowsRemovedByFilter() {
		List<Integer> removed = JsonPath.read(json, "$..['Rows Removed by Filter']");
		return removed.stream().mapToLong(Integer::longValue).sum();
	}

	/** The plan as psql would print it. */
	@Override
	public String toString() {
		return text;
	}

}
