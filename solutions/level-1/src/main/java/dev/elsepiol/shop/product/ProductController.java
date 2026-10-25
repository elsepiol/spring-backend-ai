package dev.elsepiol.shop.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

	/**
	 * Conditional GET. The response carries an ETag (the product's version). A client that has
	 * a copy sends it back as If-None-Match; if it still matches, Spring answers
	 * 304 Not Modified with no body, because the ResponseEntity has an ETag and the method is GET.
	 *
	 * Cache-Control: no-cache means "you may keep a copy, but check with me before using it".
	 * Without our own Cache-Control, Spring Security would send "no-store" and browsers would
	 * never keep a copy to revalidate.
	 */
	@GetMapping("/{id}")
	public ResponseEntity<ProductResponse> get(@PathVariable long id) {
		ProductResponse product = service.getById(id);
		return ResponseEntity.ok().eTag(etag(product)).cacheControl(CacheControl.noCache()).body(product);
	}

	/**
	 * PUT replaces the product's editable fields. If-Match is optional: send the ETag you read,
	 * and the update only happens if nobody changed the product since. Otherwise 412.
	 */
	@PutMapping("/{id}")
	public ResponseEntity<ProductResponse> update(@PathVariable long id,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody UpdateProductRequest request) {
		ProductResponse updated = service.update(id, request, expectedVersion(id, ifMatch));
		return ResponseEntity.ok().eTag(etag(updated)).body(updated);
	}

	/** GET /api/v1/products?page=0&size=20 (sorted by id so pages are stable). */
	@GetMapping
	public PageResponse<ProductResponse> list(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
		return service.list(pageable);
	}

	/**
	 * GET /api/v1/products/scroll?size=20, then the same with &cursor={nextCursor of the last page}.
	 * Keyset pagination, cheapest first. Every page costs the same, however deep you go.
	 * "/scroll" does not clash with "/{id}": Spring prefers the more specific literal path.
	 */
	@GetMapping("/scroll")
	public CursorPage<ProductResponse> scroll(@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return service.scrollByPrice(cursor, size);
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

	/** A strong ETag is a quoted string. Ours is the version number: "0", "1", "2"... */
	private static String etag(ProductResponse product) {
		return "\"" + product.version() + "\"";
	}

	/**
	 * Reads If-Match. No header, or "*" ("any version"), means no check. Anything that is not
	 * one of our ETags can never match the current version, so it is answered with 412.
	 */
	private static Long expectedVersion(long id, String ifMatch) {
		if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
			return null;
		}
		String tag = ifMatch.trim();
		if (tag.length() > 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
			try {
				return Long.parseLong(tag.substring(1, tag.length() - 1));
			}
			catch (NumberFormatException notOurs) {
				// falls through to 412
			}
		}
		throw new ProductChangedException(id);
	}

}
