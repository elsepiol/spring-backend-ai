package dev.elsepiol.shop.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records "this happened, deal with it later". Must be called INSIDE the transaction that makes
 * the change (MANDATORY fails loudly otherwise): that is the whole point of the pattern.
 *
 * The tempting alternative, "commit the order, then send the email / publish to Kafka", is two
 * writes to two systems ("dual write"). If the process dies between them, or the second one
 * fails, the order exists and the email never goes; do them the other way round and the email
 * goes for an order that then rolls back. Writing the event into the SAME database transaction
 * makes both happen or neither.
 */
@Component
public class Outbox {

	private final JdbcTemplate jdbc;

	public Outbox(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void add(String type, long aggregateId, String payloadJson) {
		// JdbcTemplate joins the surrounding JPA transaction (same connection), so this row
		// commits or rolls back together with the order.
		jdbc.update("insert into outbox_events (type, aggregate_id, payload) values (?, ?, ?)", type, aggregateId,
				payloadJson);
	}

}
