package dev.elsepiol.shop.product;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * You write only this interface. Spring Data generates the implementation at
 * startup. JpaRepository already gives save, findById, findAll(Pageable), delete...
 * Methods named by convention, like existsBySku, become SQL automatically:
 * select exists(select 1 from products where sku = ?)
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

	/**
	 * Keyset pagination, cheapest first: "the next :limit products after this (price, id)".
	 *
	 * (price_cents, id) > (:price, :id) is a ROW comparison: price first, id only to break
	 * ties. PostgreSQL can answer it by jumping into idx_products_price_id at that position,
	 * so page 10,000 costs the same as page 1. The tempting spelling
	 * "price_cents > :price or (price_cents = :price and id > :id)" means the same thing but
	 * the planner cannot jump with it, and ends up reading every row before the cursor.
	 *
	 * Plain SQL (not JPQL) so that what you read here is exactly what runs, and so that
	 * ProductQueryPlanTest can EXPLAIN this very string.
	 */
	String SCROLL_BY_PRICE_SQL = """
			select * from products
			where (price_cents, id) > (:price, :id)
			order by price_cents, id
			limit :limit""";

	boolean existsBySku(String sku);

	Page<Product> findByNameContainingIgnoreCase(String q, Pageable pageable);

	@Query(value = SCROLL_BY_PRICE_SQL, nativeQuery = true)
	List<Product> scrollByPrice(@Param("price") long price, @Param("id") long id, @Param("limit") int limit);

}
