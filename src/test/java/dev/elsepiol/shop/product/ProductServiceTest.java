package dev.elsepiol.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * A plain unit test: no Spring, no database. The repository is replaced by a
 * Mockito fake, so this runs in milliseconds and tests only our own logic.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	@Mock
	private ProductRepository repository;

	private ProductService service() {
		return new ProductService(repository);
	}

	private static final CreateProductRequest REQUEST = new CreateProductRequest("MUG-001", "Blue Mug", "A mug", 49900);

	@Test
	void createSavesAndReturnsTheProduct() {
		when(repository.existsBySku("MUG-001")).thenReturn(false);
		when(repository.saveAndFlush(any(Product.class))).thenAnswer(call -> call.getArgument(0));

		ProductResponse response = service().create(REQUEST);

		assertThat(response.sku()).isEqualTo("MUG-001");
		assertThat(response.priceCents()).isEqualTo(49900);
	}

	@Test
	void createRejectsAnExistingSkuWithoutTouchingTheDatabaseWrite() {
		when(repository.existsBySku("MUG-001")).thenReturn(true);

		assertThatThrownBy(() -> service().create(REQUEST)).isInstanceOf(DuplicateSkuException.class);

		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void createTurnsALostRaceIntoTheSameDuplicateError() {
		// Two requests passed the early check at once; the database constraint catches the loser.
		when(repository.existsBySku("MUG-001")).thenReturn(false);
		when(repository.saveAndFlush(any(Product.class))).thenThrow(new DataIntegrityViolationException("uq_products_sku"));

		assertThatThrownBy(() -> service().create(REQUEST)).isInstanceOf(DuplicateSkuException.class);
	}

	@Test
	void getByIdThrowsWhenMissing() {
		when(repository.findById(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().getById(42L)).isInstanceOf(ProductNotFoundException.class)
				.hasMessageContaining("42");
	}

}
