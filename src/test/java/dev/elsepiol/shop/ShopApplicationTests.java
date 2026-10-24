package dev.elsepiol.shop;

import dev.elsepiol.shop.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Starts the whole application, including Flyway migrations, against a real PostgreSQL. */
@SpringBootTest
class ShopApplicationTests extends PostgresIntegrationTest {

	@Test
	void contextLoads() {
	}

}
