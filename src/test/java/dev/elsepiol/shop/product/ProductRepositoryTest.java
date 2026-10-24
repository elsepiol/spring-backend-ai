package dev.elsepiol.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.elsepiol.shop.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Data layer only, against real PostgreSQL. Each test runs in a transaction that
 * is rolled back afterwards, so tests do not leak data into each other.
 * replace = NONE: use the Testcontainers database, do not swap in an embedded one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryTest extends PostgresIntegrationTest {

	@Autowired
	private ProductRepository repository;

	@Test
	void savesAndFindsAProduct() {
		Product saved = repository.saveAndFlush(new Product("REPO-001", "Plate", null, 1200));

		assertThat(saved.getId()).isNotNull();
		assertThat(repository.findById(saved.getId())).isPresent();
		assertThat(repository.existsBySku("REPO-001")).isTrue();
		assertThat(repository.existsBySku("NOPE")).isFalse();
	}

	@Test
	void databaseRejectsADuplicateSkuEvenIfTheServiceCheckIsBypassed() {
		repository.saveAndFlush(new Product("REPO-002", "Cup", null, 500));

		assertThatThrownBy(() -> repository.saveAndFlush(new Product("REPO-002", "Other cup", null, 600)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
