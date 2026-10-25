package dev.elsepiol.shop.db;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Level 5: send read-only work to a read replica. Only active when app.datasource.replica.url is
 * set; without it the app has one database, exactly as before.
 *
 * A replica is a copy of the primary database that PostgreSQL keeps up to date by streaming
 * the primary's write-ahead log to it. It can answer reads but not writes, and it is always a
 * little BEHIND (milliseconds usually, seconds or more under load). Spreading reads over replicas
 * takes load off the primary, which still has to do every write.
 *
 * The routing rule: a @Transactional(readOnly = true) transaction runs on the replica, everything
 * else on the primary. LazyConnectionDataSourceProxy makes that work: it hands Hibernate a
 * placeholder connection, and only when the first SQL statement runs does it fetch a real one,
 * from the replica pool if the transaction was marked read-only by then, else from the primary.
 *
 * So "readOnly = true" now also means "a slightly stale answer is acceptable". Reads that must
 * see the latest data (login, a customer's own orders, the idempotency check, filling the
 * product cache) are deliberately NOT read-only. ReadReplicaTest proves each one.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${app.datasource.replica.url:}' != ''")
public class ReadReplicaConfig {

	/** The primary pool: spring.datasource.* and spring.datasource.hikari.*, as without a replica. */
	@Bean
	@ConfigurationProperties("spring.datasource.hikari")
	HikariDataSource primaryDataSource(DataSourceProperties properties,
			ObjectProvider<JdbcConnectionDetails> connectionDetails) {
		HikariDataSource primary = new HikariDataSource();
		JdbcConnectionDetails details = connectionDetails.getIfAvailable(); // set by Testcontainers in tests
		primary.setJdbcUrl(details != null ? details.getJdbcUrl() : properties.determineUrl());
		primary.setUsername(details != null ? details.getUsername() : properties.determineUsername());
		primary.setPassword(details != null ? details.getPassword() : properties.determinePassword());
		return primary;
	}

	/** The replica pool: app.datasource.replica.* and app.datasource.replica.hikari.*. */
	@Bean
	@ConfigurationProperties("app.datasource.replica.hikari")
	HikariDataSource replicaDataSource(@Value("${app.datasource.replica.url}") String url,
			@Value("${app.datasource.replica.username}") String username,
			@Value("${app.datasource.replica.password}") String password) {
		HikariDataSource replica = new HikariDataSource();
		replica.setJdbcUrl(url);
		replica.setUsername(username);
		replica.setPassword(password);
		replica.setReadOnly(true);
		return replica;
	}

	/** What JPA, Flyway and JdbcTemplate use: routes each transaction to one of the two pools. */
	@Bean
	@Primary
	DataSource dataSource(HikariDataSource primaryDataSource, HikariDataSource replicaDataSource) {
		LazyConnectionDataSourceProxy routing = new LazyConnectionDataSourceProxy(primaryDataSource);
		routing.setReadOnlyDataSource(replicaDataSource);
		return routing;
	}

}
