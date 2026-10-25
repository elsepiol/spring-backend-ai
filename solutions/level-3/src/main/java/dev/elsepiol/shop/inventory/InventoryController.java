package dev.elsepiol.shop.inventory;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock lives under the product it belongs to. Reading is public; writing is covered
 * by the existing "everything else under /api/v1/products/** is ADMIN only" rule.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/stock")
public class InventoryController {

	private final InventoryService service;

	public InventoryController(InventoryService service) {
		this.service = service;
	}

	@GetMapping
	public StockResponse get(@PathVariable long productId) {
		return service.getStock(productId);
	}

	/** Sets the stock level to an exact number (PUT = replace; calling it twice is harmless). */
	@PutMapping
	public ResponseEntity<Void> set(@PathVariable long productId, @Valid @RequestBody SetStockRequest request) {
		service.setStock(productId, request.quantity());
		return ResponseEntity.noContent().build();
	}

	/** SOLUTION (Exercise 3B): relative change, e.g. {"delta": -2}. */
	@PostMapping("/adjust")
	public ResponseEntity<Void> adjust(@PathVariable long productId, @Valid @RequestBody AdjustStockRequest request) {
		service.adjust(productId, request.delta());
		return ResponseEntity.noContent().build();
	}

}
