package dev.elsepiol.shop.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Stock changes are single SQL statements that check and change in one step.
 * That is the whole trick: there is no gap between "is there enough?" and
 * "take it" for another request to slip into.
 */
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

	/**
	 * Takes qty units if, and only if, that many are available. Returns the number of
	 * rows changed: 1 = success, 0 = not enough stock (or no stock record at all).
	 *
	 * PostgreSQL locks the row while this runs, so two concurrent calls are applied one
	 * after the other, and the second one re-checks "quantity >= qty" against the
	 * result of the first.
	 */
	@Modifying
	@Query("update Inventory i set i.quantity = i.quantity - :qty where i.productId = :productId and i.quantity >= :qty")
	int tryDecrement(@Param("productId") long productId, @Param("qty") int qty);

	/** Puts qty units back (cancelled order). Returns rows changed. */
	@Modifying
	@Query("update Inventory i set i.quantity = i.quantity + :qty where i.productId = :productId")
	int increment(@Param("productId") long productId, @Param("qty") int qty);

	/** Create the stock row or overwrite it, atomically ("upsert"). */
	@Modifying
	@Query(value = "insert into inventory (product_id, quantity) values (:productId, :qty) "
			+ "on conflict (product_id) do update set quantity = excluded.quantity", nativeQuery = true)
	void upsert(@Param("productId") long productId, @Param("qty") int qty);

}
