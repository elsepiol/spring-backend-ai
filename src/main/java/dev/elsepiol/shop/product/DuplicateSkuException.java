package dev.elsepiol.shop.product;

/** Thrown when a SKU is already taken. The ApiExceptionHandler turns it into HTTP 409. */
public class DuplicateSkuException extends RuntimeException {

	public DuplicateSkuException(String sku) {
		super("A product with SKU " + sku + " already exists");
	}

}
