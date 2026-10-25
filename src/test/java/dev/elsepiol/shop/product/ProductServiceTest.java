package dev.elsepiol.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

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

	// ---- Level 4 ----

	/** A product as if loaded from the database (which is where the id and version come from). */
	private static Product stored(long id, long priceCents, long version) {
		Product product = new Product("SKU-" + id, "Thing", null, priceCents);
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "version", version);
		return product;
	}

	@Test
	void updateBasedOnAnOldVersionIsRefusedAndWritesNothing() {
		when(repository.findById(7L)).thenReturn(Optional.of(stored(7, 100, 4)));

		assertThatThrownBy(() -> service().update(7L, new UpdateProductRequest("New", null, 200), 3L))
				.isInstanceOf(ProductChangedException.class);

		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void updateWithTheCurrentVersionOrNoneIsApplied() {
		Product product = stored(7, 100, 4);
		when(repository.findById(7L)).thenReturn(Optional.of(product));
		when(repository.saveAndFlush(product)).thenReturn(product);

		assertThat(service().update(7L, new UpdateProductRequest("New", null, 200), 4L).priceCents()).isEqualTo(200);
		assertThat(service().update(7L, new UpdateProductRequest("Newer", null, 300), null).name()).isEqualTo("Newer");
	}

	@Test
	void scrollFromTheStartAsksForOneRowMoreThanThePage() {
		service().scrollByPrice(null, 20);

		verify(repository).scrollByPrice(Long.MIN_VALUE, Long.MIN_VALUE, 21);
	}

	@Test
	void scrollGivesANextCursorPointingAtTheLastRowShownWhenThereAreMore() {
		// Asked for 3 + 1 rows and got 4: there is a next page.
		List<Product> rows = LongStream.rangeClosed(1, 4).mapToObj(i -> stored(i, i * 100, 0)).toList();
		when(repository.scrollByPrice(anyLong(), anyLong(), anyInt())).thenReturn(rows);

		CursorPage<ProductResponse> page = service().scrollByPrice(null, 3);

		assertThat(page.items()).extracting(ProductResponse::id).containsExactly(1L, 2L, 3L);
		assertThat(Cursor.decode(page.nextCursor(), 2)).containsExactly(300L, 3L);
	}

	@Test
	void scrollHasNoNextCursorOnTheLastPage() {
		when(repository.scrollByPrice(anyLong(), anyLong(), anyInt())).thenReturn(List.of(stored(1, 100, 0)));

		assertThat(service().scrollByPrice(Cursor.encode(50, 9), 3).nextCursor()).isNull();
		verify(repository).scrollByPrice(50, 9, 4);
	}

}
