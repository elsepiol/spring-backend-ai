package dev.elsepiol.shop.product;

/**
 * The client sent If-Match with an ETag that is no longer current: somebody changed the product
 * after this client read it. HTTP 412 Precondition Failed. The client should GET the product
 * again, look at the new values and decide, instead of blindly overwriting the other change.
 */
public class ProductChangedException extends RuntimeException {

	public ProductChangedException(long id) {
		super("Product " + id + " was changed by someone else. Fetch it again and retry");
	}

}
