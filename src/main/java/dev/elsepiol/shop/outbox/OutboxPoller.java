package dev.elsepiol.shop.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the worker in the background: every poll interval, batches until nothing is due.
 * app.outbox.worker.enabled=false switches it off (tests that count SQL statements, or that
 * want to run the worker by hand, do that).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.outbox.worker.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

	private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

	private final OutboxWorker worker;
	private final OutboxProperties properties;

	public OutboxPoller(OutboxWorker worker, OutboxProperties properties) {
		this.worker = worker;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms}")
	void poll() {
		try {
			while (worker.runOnce() == properties.batchSize()) {
				// a full batch: there may be more due right now, go again without waiting
			}
		}
		catch (RuntimeException ex) {
			// Database busy or down: try again next tick. The events are safe in their table.
			log.warn("Outbox poll failed: {}", ex.getMessage());
		}
	}

}
