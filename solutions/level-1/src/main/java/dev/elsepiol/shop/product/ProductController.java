package dev.elsepiol.shop.product;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** HTTP layer only: parse the request, call the service, shape the response. */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

	private final ProductService service;

	public ProductController(ProductService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
		ProductResponse created = service.create(request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}")
				.buildAndExpand(created.id())
				.toUri();
		return ResponseEntity.created(location).body(created); // 201 + Location header
	}

	@GetMapping("/{id}")
	public ProductResponse get(@PathVariable long id) {
		return service.getById(id);
	}

	/** GET /api/v1/products?page=0&size=20 (sorted by id so pages are stable). */
	@GetMapping
	public PageResponse<ProductResponse> list(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
		return service.list(pageable);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		service.delete(id);
		return ResponseEntity.noContent().build(); // 204
	}

	// "/search" does not clash with "/{id}": Spring prefers the more specific literal path.
	@GetMapping("/search")
	public PageResponse<ProductResponse> search(@RequestParam String q,
			@PageableDefault(size = 20, sort = "id") Pageable pageable) {
		return service.search(q, pageable);
	}

}
