package dev.elsepiol.shop.cache;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Switches on Spring's cache annotations (@Cacheable, @CacheEvict). Which cache library backs
 * them, and how big and how fresh the caches are, is set in application.properties
 * (spring.cache.*): here, Caffeine, an in-memory cache inside this JVM.
 *
 * order: @Cacheable and @Transactional both work by wrapping the bean in a proxy, and the one
 * with the smaller order number is the OUTER one. Transactions use LOWEST_PRECEDENCE, so
 * LOWEST_PRECEDENCE - 1 puts the cache just outside the transaction:
 *   - a cache hit returns before any transaction starts, so it borrows no database connection;
 *   - @CacheEvict runs after the transaction has committed, never before.
 * With equal orders (the default) the nesting depends on which proxy Spring happens to register
 * first: it works here today, by accident. Say what you mean. If the transaction ends up outside,
 * a cache hit still runs no SQL but opens a session and borrows a connection, and
 * ProductCacheTest.aSecondReadIsServedFromTheCacheWithoutTouchingTheDatabase fails.
 */
@Configuration
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 1)
public class CacheConfig {
}
