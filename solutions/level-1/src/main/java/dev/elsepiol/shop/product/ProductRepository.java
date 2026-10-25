package dev.elsepiol.shop.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * You write only this interface. Spring Data generates the implementation at
 * startup. JpaRepository already gives save, findById, findAll(Pageable), delete...
 * Methods named by convention, like existsBySku, become SQL automatically:
 * select exists(select 1 from products where sku = ?)
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

	boolean existsBySku(String sku);

	Page<Product> findByNameContainingIgnoreCase(String q, Pageable pageable);

}
