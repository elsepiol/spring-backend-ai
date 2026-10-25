package dev.elsepiol.shop.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
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
 * disabledWithoutDocker = true: if Docker is not running on the machine, these
 * tests are skipped instead of failing. CI always has Docker, so they always run there.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	static {
		// Guard so that merely loading this class on a machine without Docker does not fail.
		if (DockerClientFactory.instance().isDockerAvailable()) {
			postgres.start();
		}
	}

}
