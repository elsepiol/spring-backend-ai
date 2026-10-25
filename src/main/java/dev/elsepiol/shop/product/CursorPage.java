package dev.elsepiol.shop.product;

import java.util.List;

/**
 * One page of a keyset ("scroll") listing. Instead of a page number the client gets an opaque
 * nextCursor and sends it back to get the rows that come after this page. null means there
 * are no more rows. There is deliberately no total count: counting every row is the expensive
 * part of offset pagination, and infinite-scroll screens do not need it.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {
}
