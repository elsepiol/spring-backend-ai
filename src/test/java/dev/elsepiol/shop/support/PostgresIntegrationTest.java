package dev.elsepiol.shop.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need a real database.
 *
 * Testcontainers starts a throwaway PostgreSQL in Docker before the tests and
 * deletes it afterwards. @ServiceConnection tells Spring Boot to point the
 * application's datasource at that container automatically.
 *
 * disabledWithoutDocker = true: if Docker is not running on the machine, these
 * tests are skipped instead of failing. CI always has Docker, so they always
 * run there.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

}
