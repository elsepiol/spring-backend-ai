package dev.elsepiol.shop.inventory;

import dev.elsepiol.shop.product.ProductNotFoundException;
import dev.elsepiol.shop.product.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

	private final InventoryRepository inventory;
	private final ProductRepository products;

	public InventoryService(InventoryRepository inventory, ProductRepository products) {
		this.inventory = inventory;
		this.products = products;
	}

	@Transactional
	public void setStock(long productId, int quantity) {
		requireProduct(productId);
		inventory.upsert(productId, quantity);
	}

	@Transactional(readOnly = true)
	public StockResponse getStock(long productId) {
		requireProduct(productId);
		int quantity = inventory.findById(productId).map(Inventory::getQuantity).orElse(0);
		return new StockResponse(productId, quantity);
	}

	/** SOLUTION (Exercise 3B). One atomic statement: no read-then-write gap. */
	@Transactional
	public void adjust(long productId, int delta) {
		if (inventory.tryAdjust(productId, delta) == 1) {
			return;
		}
		// Zero rows changed: either there is no stock record, or the result would be negative.
		if (!inventory.existsById(productId)) {
			throw new ProductNotFoundException(productId);
		}
		throw new InsufficientStockException(productId);
	}

	/** SOLUTION (Exercise 3A). */
	@Transactional(readOnly = true)
	public List<LowStockItem> lowStock(int threshold) {
		return inventory.findLow(threshold).stream()
				.map(i -> new LowStockItem(i.getProductId(), i.getQuantity()))
				.toList();
	}

	private void requireProduct(long productId) {
		if (!products.existsById(productId)) {
			throw new ProductNotFoundException(productId);
		}
	}

}
