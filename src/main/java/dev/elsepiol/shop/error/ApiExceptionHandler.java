package dev.elsepiol.shop.error;

import dev.elsepiol.shop.auth.DuplicateEmailException;
import dev.elsepiol.shop.auth.InvalidCredentialsException;
import dev.elsepiol.shop.auth.InvalidPasswordException;
import dev.elsepiol.shop.inventory.InsufficientStockException;
import dev.elsepiol.shop.order.IdempotencyKeyReuseException;
import dev.elsepiol.shop.order.InvalidOrderException;
import dev.elsepiol.shop.order.OrderNotCancellableException;
import dev.elsepiol.shop.order.OrderNotFoundException;
import dev.elsepiol.shop.product.DuplicateSkuException;
import dev.elsepiol.shop.product.InvalidCursorException;
import dev.elsepiol.shop.product.ProductChangedException;
import dev.elsepiol.shop.product.ProductNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One place that turns exceptions into consistent error responses, using the
 * standard "problem details" JSON format (RFC 9457, content type
 * application/problem+json). The parent class already handles Spring's own
 * exceptions (bad JSON, wrong method, failed validation of path variables...).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(ProductNotFoundException.class)
	ProblemDetail handleNotFound(ProductNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Resource not found");
		return problem;
	}

	@ExceptionHandler(InsufficientStockException.class)
	ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Insufficient stock");
		problem.setProperty("productId", ex.getProductId());
		return problem;
	}

	@ExceptionHandler(InvalidOrderException.class)
	ProblemDetail handleInvalidOrder(InvalidOrderException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid order");
		return problem;
	}

	@ExceptionHandler(OrderNotFoundException.class)
	ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Resource not found");
		return problem;
	}

	@ExceptionHandler(IdempotencyKeyReuseException.class)
	ProblemDetail handleKeyReuse(IdempotencyKeyReuseException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
		problem.setTitle("Idempotency key reuse");
		return problem;
	}

	@ExceptionHandler(OrderNotCancellableException.class)
	ProblemDetail handleNotCancellable(OrderNotCancellableException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Conflict");
		return problem;
	}

	/**
	 * Safety net for any database constraint we did not handle specifically, for example
	 * deleting a product that past orders refer to. Deliberately vague: constraint names
	 * and SQL are internal details that should not leak to clients.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail handleIntegrityViolation(DataIntegrityViolationException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The request conflicts with existing data");
		problem.setTitle("Conflict");
		return problem;
	}

	@ExceptionHandler(DuplicateEmailException.class)
	ProblemDetail handleDuplicateEmail(DuplicateEmailException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Conflict");
		return problem;
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
		problem.setTitle("Unauthorized");
		return problem;
	}

	@ExceptionHandler(InvalidPasswordException.class)
	ProblemDetail handleInvalidPassword(InvalidPasswordException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Bad Request");
		return problem;
	}

	@ExceptionHandler(DuplicateSkuException.class)
	ProblemDetail handleDuplicate(DuplicateSkuException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Conflict");
		return problem;
	}

	@ExceptionHandler(InvalidCursorException.class)
	ProblemDetail handleInvalidCursor(InvalidCursorException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Bad Request");
		return problem;
	}

	/** If-Match did not match the current ETag: the client's copy is out of date. */
	@ExceptionHandler(ProductChangedException.class)
	ProblemDetail handleProductChanged(ProductChangedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, ex.getMessage());
		problem.setTitle("Precondition Failed");
		return problem;
	}

	/**
	 * @Version caught two updates of the same row racing each other, and this one lost. Nothing
	 * was written; the client can fetch the new state and try again.
	 */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The resource was changed by another request at the same time. Fetch it again and retry");
		problem.setTitle("Conflict");
		return problem;
	}

	/**
	 * No database connection could be had in time: the pool is exhausted (spring.datasource.hikari
	 * .connection-timeout) or the database is down. 503 + Retry-After tells clients and load
	 * balancers "overloaded, back off and retry", which is true, instead of a generic 500.
	 */
	@ExceptionHandler({ CannotCreateTransactionException.class, DataAccessResourceFailureException.class })
	ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"The service is busy. Please retry shortly");
		problem.setTitle("Service Unavailable");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
	}

	/** Failed @Valid on a request body: add which fields were wrong and why. */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ex.getBody();
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

}
