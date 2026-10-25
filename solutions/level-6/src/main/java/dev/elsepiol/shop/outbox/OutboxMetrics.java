package dev.elsepiol.shop.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SOLUTION (Exercise 6A). How far behind the background work is.
 *
 * A gauge is not stored: its function runs every time the value is read (each Prometheus
 * scrape, typically every 15-30 s, per instance). So the queries must stay cheap. Counting
 * PENDING rows uses the partial index idx_outbox_due; DEAD rows should be few.
 */
@Component
public class OutboxMetrics implements MeterBinder {

	private final JdbcTemplate jdbc;

	public OutboxMetrics(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("shop.outbox.pending", () -> count("PENDING"))
				.description("Outbox events waiting to be handled")
				.register(registry);
		Gauge.builder("shop.outbox.dead", () -> count("DEAD"))
				.description("Outbox events that failed too often and need a human")
				.register(registry);
		Gauge.builder("shop.outbox.oldest.pending.age", this::oldestPendingAgeSeconds)
				.description("How long the oldest waiting event has been waiting")
				.baseUnit("seconds")
				.register(registry);
	}

	private double count(String status) {
		return jdbc.queryForObject("select count(*) from outbox_events where status = ?", Long.class, status);
	}

	private double oldestPendingAgeSeconds() {
		return jdbc.queryForObject("select coalesce(extract(epoch from now() - min(created_at)), 0) "
				+ "from outbox_events where status = 'PENDING'", Double.class);
	}

}
