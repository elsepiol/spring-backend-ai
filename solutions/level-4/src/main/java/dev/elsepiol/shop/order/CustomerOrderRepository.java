package dev.elsepiol.shop.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

	Optional<CustomerOrder> findByUserIdAndIdempotencyKey(long userId, String idempotencyKey);

	Page<CustomerOrder> findByUserId(long userId, Pageable pageable);

	/**
	 * SOLUTION (Exercise 4A). Keyset: "this user's orders older than beforeId, newest first".
	 * Becomes: where user_id = ? and id < ? order by id desc fetch first ? rows only
	 */
	List<CustomerOrder> findByUserIdAndIdLessThanOrderByIdDesc(long userId, long beforeId, Limit limit);

	/**
	 * Moves an order from one status to another ONLY if it is currently in the expected one.
	 * Returns 1 if it moved, 0 if someone else already changed it. Same atomic
	 * "check and change in one statement" idea as the stock update: it is what stops two
	 * simultaneous cancels from both putting the stock back.
	 *
	 * clearAutomatically: a bulk update bypasses Hibernate's cache of loaded objects, so we
	 * clear that cache to avoid reading a stale status afterwards.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update CustomerOrder o set o.status = :to where o.id = :id and o.status = :from")
	int transition(@Param("id") long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to);

}
