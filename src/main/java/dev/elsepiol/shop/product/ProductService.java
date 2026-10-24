package dev.elsepiol.shop.product;

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

	@Transactional(readOnly = true)
	public ProductResponse getById(long id) {
		return repository.findById(id).map(ProductResponse::from).orElseThrow(() -> new ProductNotFoundException(id));
	}

	@Transactional(readOnly = true)
	public PageResponse<ProductResponse> list(Pageable pageable) {
		Page<Product> page = repository.findAll(pageable);
		return PageResponse.from(page.map(ProductResponse::from));
	}

	@Transactional
	public void delete(long id) {
		if (!repository.existsById(id)) {
			throw new ProductNotFoundException(id);
		}
		repository.deleteById(id);
	}

	@Transactional(readOnly = true)
	public PageResponse<ProductResponse> search(String q, Pageable pageable) {
		return PageResponse.from(repository.findByNameContainingIgnoreCase(q, pageable).map(ProductResponse::from));
	}

}
