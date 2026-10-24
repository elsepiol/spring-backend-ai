package dev.elsepiol.shop.product;

/** Thrown when a product id does not exist. The ApiExceptionHandler turns it into HTTP 404. */
public class ProductNotFoundException extends RuntimeException {

	public ProductNotFoundException(long id) {
		super("Product " + id + " not found");
	}

}
