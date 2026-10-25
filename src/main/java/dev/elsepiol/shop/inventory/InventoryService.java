package dev.elsepiol.shop.inventory;

import dev.elsepiol.shop.product.ProductNotFoundException;
import dev.elsepiol.shop.product.ProductRepository;
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

	private void requireProduct(long productId) {
		if (!products.existsById(productId)) {
			throw new ProductNotFoundException(productId);
		}
	}

}
