package dev.elsepiol.shop.product;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encodes the position of the last row of a page ("price 1200, id 42") as a short opaque
 * string such as "MTIwMDo0Mg". Opaque on purpose: clients must not build or edit cursors, so
 * we stay free to change what is inside (another sort column, a version prefix) later.
 * It is not a secret and not tamper-proof; a client that edits one only gets a different page.
 */
public final class Cursor {

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private Cursor() {
	}

	public static String encode(long... values) {
		String joined = String.join(":", Arrays.stream(values).mapToObj(Long::toString).toList());
		return ENCODER.encodeToString(joined.getBytes(StandardCharsets.UTF_8));
	}

	/** The values inside the cursor; exactly {@code expected} of them, or InvalidCursorException. */
	public static long[] decode(String cursor, int expected) {
		try {
			String joined = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			long[] values = Arrays.stream(joined.split(":", -1)).mapToLong(Long::parseLong).toArray();
			if (values.length != expected) {
				throw new InvalidCursorException();
			}
			return values;
		}
		catch (IllegalArgumentException ex) { // bad base64 or not a number (NumberFormatException is one)
			throw new InvalidCursorException();
		}
	}

}
