package dev.elsepiol.shop.outbox;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Takes due events from outbox_events and runs their handlers. Every app instance runs one
 * (OutboxPoller), and they never step on each other:
 *
 *   1. CLAIM: one UPDATE marks a batch as taken by pushing available_at forward by the lease.
 *      FOR UPDATE SKIP LOCKED in its subquery means two workers claiming at the same instant
 *      skip each other's rows instead of waiting for them, so each event goes to one worker.
 *   2. RUN each handler, outside any transaction of ours (a slow handler holds no locks and no
 *      connection while it works).
 *   3. RECORD the outcome: DONE; or, after a failure, retry later with exponential backoff;
 *      or DEAD after maxAttempts, for a human to look at.
 *
 * If the worker dies between 1 and 3, nothing marks the events DONE, the lease runs out and
 * another worker takes them: "at least once" delivery, which is why handlers are idempotent.
 *
 * Each step is a single SQL statement in auto-commit mode, so an idle poll costs one cheap,
 * indexed query and no transaction.
 */
@Component
public class OutboxWorker {

	private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

	static final String CLAIM_SQL = """
			update outbox_events
			set available_at = now() + make_interval(secs => ?), attempts = attempts + 1
			where id in (
			    select id from outbox_events
			    where status = 'PENDING' and available_at <= now()
			    order by available_at, id
			    limit ?
			    for update skip locked)
			returning id, type, aggregate_id, payload, attempts""";

	private final JdbcTemplate jdbc;
	private final OutboxProperties properties;
	private final Map<String, OutboxHandler> handlers;

	public OutboxWorker(JdbcTemplate jdbc, OutboxProperties properties, List<OutboxHandler> handlers) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::type, Function.identity()));
	}

	/** Claims and runs one batch. Returns how many events it took (0 = nothing was due). */
	public int runOnce() {
		List<OutboxMessage> batch = jdbc.query(CLAIM_SQL,
				(rs, row) -> new OutboxMessage(rs.getLong("id"), rs.getString("type"), rs.getLong("aggregate_id"),
						rs.getString("payload"), rs.getInt("attempts")),
				properties.lease().toMillis() / 1000.0, properties.batchSize());
		batch.forEach(this::run);
		return batch.size();
	}

	private void run(OutboxMessage message) {
		OutboxHandler handler = handlers.get(message.type());
		try {
			if (handler == null) {
				// Treated as a normal failure, not thrown away at once: during a rolling deploy an OLD
				// instance can see an event type only the new version knows. Retrying gives a new
				// instance the chance to take it; if none ever does, it ends up DEAD, not lost.
				throw new IllegalStateException("No handler for event type " + message.type());
			}
			handler.handle(message);
			jdbc.update("update outbox_events set status = 'DONE', processed_at = now(), last_error = null "
					+ "where id = ?", message.id());
		}
		catch (Exception ex) {
			String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
			if (message.attempts() >= properties.maxAttempts()) {
				markDead(message, error);
			}
			else {
				// 1x, 2x, 4x, 8x... the base delay, so a struggling downstream gets room to recover
				// instead of being hammered by retries.
				double delaySeconds = properties.retryBaseDelay().toMillis() / 1000.0 * Math.pow(2, message.attempts() - 1);
				jdbc.update("update outbox_events set available_at = now() + make_interval(secs => ?), last_error = ? "
						+ "where id = ?", delaySeconds, truncate(error), message.id());
				log.warn("Outbox event {} ({}) failed on attempt {}, retrying in {} s: {}", message.id(),
						message.type(), message.attempts(), delaySeconds, error);
			}
		}
	}

	private void markDead(OutboxMessage message, String error) {
		jdbc.update("update outbox_events set status = 'DEAD', last_error = ? where id = ?", truncate(error),
				message.id());
		log.error("Outbox event {} ({}) is DEAD after {} attempt(s): {}", message.id(), message.type(),
				message.attempts(), error);
	}

	private static String truncate(String error) {
		return error.length() <= 500 ? error : error.substring(0, 500);
	}

}
