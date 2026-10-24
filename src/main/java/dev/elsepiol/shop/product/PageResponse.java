package dev.elsepiol.shop.product;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Our own, stable JSON shape for a page of results. We do not return Spring's
 * Page object directly because its JSON layout is an internal detail that could
 * change between versions and break clients.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
