package dev.elsepiol.shop.support;

import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A REAL streaming replica of the shared test database (PostgresIntegrationTest), started on
 * first use. It is made the way you would make one by hand:
 *
 *   pg_basebackup copies the primary's data directory over a replication connection, and -R
 *   writes the settings that make the copy start as a standby that keeps streaming the
 *   primary's write-ahead log (WAL) and replaying it.
 *
 * Replication lag is usually milliseconds, far too short to test reliably. pauseReplay() stops
 * the replica from APPLYING the WAL it receives, so a test can make "the replica is behind"
 * happen on purpose; resumeReplay() lets it catch up.
 */
public final class PostgresReplica {

	private static final String SCRIPT = """
			set -e
			mkdir -p "$PGDATA" && chown postgres:postgres "$PGDATA" && chmod 700 "$PGDATA"
			until su-exec postgres pg_basebackup -h primary -U "$PRIMARY_USER" -D "$PGDATA" -R -X stream; do sleep 1; done
			exec su-exec postgres postgres
			""";

	private static final GenericContainer<?> replica = new GenericContainer<>("postgres:17-alpine")
			.withNetwork(PostgresIntegrationTest.NETWORK)
			.withEnv("PRIMARY_USER", PostgresIntegrationTest.postgres.getUsername())
			.withEnv("PGPASSWORD", PostgresIntegrationTest.postgres.getPassword())
			.withExposedPorts(5432)
			.withCommand("sh", "-c", SCRIPT)
			.waitingFor(Wait.forLogMessage(".*ready to accept read-only connections.*", 1)
					.withStartupTimeout(Duration.ofMinutes(2)));

	private PostgresReplica() {
	}

	public static synchronized String jdbcUrl() {
		if (!replica.isRunning()) {
			replica.start();
		}
		return "jdbc:postgresql://" + replica.getHost() + ":" + replica.getMappedPort(5432) + "/"
				+ PostgresIntegrationTest.postgres.getDatabaseName();
	}

	public static String username() {
		return PostgresIntegrationTest.postgres.getUsername(); // a physical copy: same users as the primary
	}

	public static String password() {
		return PostgresIntegrationTest.postgres.getPassword();
	}

	private static String primaryJdbcUrl() {
		return PostgresIntegrationTest.postgres.getJdbcUrl();
	}

	/** The replica keeps receiving WAL but stops applying it: from now on it falls behind. */
	public static void pauseReplay() {
		execute(jdbcUrl(), "select pg_wal_replay_pause()");
	}

	/** Applies everything received meanwhile, and waits until it has replayed all the primary wrote. */
	public static void resumeReplay() {
		execute(jdbcUrl(), "select pg_wal_replay_resume()");
		String target = query(primaryJdbcUrl(), "select pg_current_wal_lsn()::text");
		await().atMost(Duration.ofSeconds(30)).until(() -> "t".equals(
				query(jdbcUrl(), "select pg_last_wal_replay_lsn() >= '" + target + "'::pg_lsn")));
	}

	private static void execute(String url, String sql) {
		query(url, sql);
	}

	private static String query(String url, String sql) {
		try (Connection connection = DriverManager.getConnection(url, username(), password());
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getString(1);
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
