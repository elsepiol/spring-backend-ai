package dev.elsepiol.shop.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need a real database.
 *
 * ONE PostgreSQL container is started the first time any such test runs and is
 * shared by all of them until the test JVM exits (Testcontainers' background
 * "Ryuk" process then removes it). It is not started and stopped per test class
 * on purpose:
 *
 *   Spring caches application contexts between test classes that have the same
 *   configuration, and a cached context keeps its database connection pool. If
 *   each class got its own container, a class that reused a cached context would
 *   find its database already shut down and every request would hang for 30
 *   seconds, then fail. (This exact bug appeared when Level 2 added a second
 *   test class with the same configuration as an existing one.)
 *
 * @ServiceConnection tells Spring Boot to point the application's datasource at
 * this container automatically.
 *
 * Level 5: the container also accepts replication connections, so PostgresReplica can attach a
 * real streaming replica to it (on a shared Docker network, as host "primary").
 *
 * The test properties below apply to every subclass. To override one, a subclass needs its own
 * @TestPropertySource: @SpringBootTest(properties = ...) has LOWER precedence and would lose.
 *
 *   - cached contexts stay alive for the whole run, so a background outbox poller in one of
 *     them would take events that a later test class expects to process itself;
 *   - many tests register and log in from the same address within seconds.
 *
 * disabledWithoutDocker = true: if Docker is not running on the machine, these
 * tests are skipped instead of failing. CI always has Docker, so they always run there.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = { "app.outbox.worker.enabled=false", "app.rate-limit.auth.capacity=1000000" })
public abstract class PostgresIntegrationTest {

	static final Network NETWORK = Network.newNetwork();

	/** Runs once when the container's database is first created: allow replicas to connect. */
	private static final String ALLOW_REPLICATION = """
			#!/bin/sh
			echo "host replication all all scram-sha-256" >> "$PGDATA/pg_hba.conf"
			""";

	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
			.withNetwork(NETWORK)
			.withNetworkAliases("primary")
			.withCopyToContainer(Transferable.of(ALLOW_REPLICATION, 0755),
					"/docker-entrypoint-initdb.d/allow-replication.sh");

	static {
		// Guard so that merely loading this class on a machine without Docker does not fail.
		if (DockerClientFactory.instance().isDockerAvailable()) {
			postgres.start();
		}
	}

}
