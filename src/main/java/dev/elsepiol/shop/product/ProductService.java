package dev.elsepiol.shop.product;

import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The service layer holds the business rules. The controller deals with HTTP,
 * the repository deals with the database, and this class sits between them.
 */
@Service
public class ProductService {

	/** The cache that holds single products by id. Configured in application.properties. */
	public static final String CACHE = "products";

	private final ProductRepository repository;

	public ProductService(ProductRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public ProductResponse create(CreateProductRequest request) {
		// Friendly early check. It is NOT enough on its own: two requests can both
		// pass it at the same moment. The UNIQUE constraint in the database is the
		// real guard, which is why we also handle the violation below.
		if (repository.existsBySku(request.sku())) {
			throw new DuplicateSkuException(request.sku());
		}
		try {
			Product saved = repository.saveAndFlush(
					new Product(request.sku(), request.name(), request.description(), request.priceCents()));
			return ProductResponse.from(saved);
		}
		catch (DataIntegrityViolationException ex) {
			throw new DuplicateSkuException(request.sku());
		}
	}

	/**
	 * Cache-aside: look in the "products" cache first; only on a miss run this method (and its
	 * query) and remember the result. The cache sits OUTSIDE the transaction (see CacheConfig),
	 * so a hit does not even borrow a database connection.
	 *
	 * sync = true: if 500 requests miss the same id at once (say, right after an update evicted
	 * a popular product), one of them loads it and the other 499 wait for that result, instead
	 * of 500 identical queries hitting the database together (a "cache stampede").
	 *
	 * A missing product throws, and exceptions are never cached, so a product created later
	 * is found at once.
	 *
	 * Why the miss reads the PRIMARY (Level 5): an update evicts the entry; if the next miss read
	 * a replica that has not replayed the update yet, it would put the OLD product back into the
	 * cache, where it would then stay for the full 10 minutes.
	 */
	@Cacheable(cacheNames = CACHE, key = "#id", sync = true)
	@Transactional // not read-only (Level 5): fill the cache from the primary, never from a lagging replica
	public ProductResponse getById(long id) {
		return repository.findById(id).map(ProductResponse::from).orElseThrow(() -> new ProductNotFoundException(id));
	}

	/**
	 * expectedVersion is the version the client last saw (from If-Match), or null to skip that
	 * check. Two separate protections against lost updates:
	 *  - the If-Match check catches a client that is working from an old copy;
	 *  - @Version on Product catches two updates racing inside this method at the same moment.
	 *
	 * @CacheEvict runs after the method returns, and because the cache sits outside the
	 * transaction, that is AFTER the commit. Evicting before the commit would let a concurrent
	 * reader put the old row straight back into the cache.
	 */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public ProductResponse update(long id, UpdateProductRequest request, Long expectedVersion) {
		Product product = repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
		if (expectedVersion != null && expectedVersion != product.getVersion()) {
			throw new ProductChangedException(id);
		}
		product.update(request.name(), request.description(), request.priceCents());
		// Flush now so the UPDATE runs (and the version goes up) before we build the response.
		return ProductResponse.from(repository.saveAndFlush(product));
	}

	@Transactional(readOnly = true)
	public PageResponse<ProductResponse> list(Pageable pageable) {
		Page<Product> page = repository.findAll(pageable);
		return PageResponse.from(page.map(ProductResponse::from));
	}

	/**
	 * Keyset pagination, cheapest first. A null cursor means "from the start". We ask for one
	 * row more than the page size: if it comes back there is a next page, and we did not need
	 * a count(*) to find that out.
	 */
	@Transactional(readOnly = true)
	public CursorPage<ProductResponse> scrollByPrice(String cursor, int size) {
		// "Before everything": every real (price, id) is greater than (MIN, MIN).
		long[] after = cursor == null ? new long[] { Long.MIN_VALUE, Long.MIN_VALUE } : Cursor.decode(cursor, 2);
		List<Product> rows = repository.scrollByPrice(after[0], after[1], size + 1);

		boolean more = rows.size() > size;
		List<Product> page = more ? rows.subList(0, size) : rows;
		String next = null;
		if (more) {
			Product last = page.get(page.size() - 1);
			next = Cursor.encode(last.getPriceCents(), last.getId());
		}
		return new CursorPage<>(page.stream().map(ProductResponse::from).toList(), next);
	}

}
