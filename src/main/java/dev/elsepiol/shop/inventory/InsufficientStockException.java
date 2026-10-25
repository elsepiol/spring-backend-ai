package dev.elsepiol.shop.inventory;

/** Not enough stock. Mapped to HTTP 409 with the product id, so a client can tell the user which item. */
public class InsufficientStockException extends RuntimeException {

	private final long productId;

	public InsufficientStockException(long productId) {
		super("Insufficient stock for product " + productId);
		this.productId = productId;
	}

	public long getProductId() {
		return productId;
	}

}
