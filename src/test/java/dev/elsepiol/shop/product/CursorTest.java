package dev.elsepiol.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CursorTest {

	@Test
	void whatGoesInComesOut() {
		String cursor = Cursor.encode(1200, 42);

		assertThat(cursor).isEqualTo("MTIwMDo0Mg"); // base64url of "1200:42", no padding
		assertThat(Cursor.decode(cursor, 2)).containsExactly(1200L, 42L);
		assertThat(Cursor.decode(Cursor.encode(Long.MIN_VALUE), 1)).containsExactly(Long.MIN_VALUE);
	}

	@Test
	void anythingWeDidNotMakeIsRejected() {
		for (String bad : new String[] { "", "!!!", "bm90IG51bWJlcnM", Cursor.encode(1, 2, 3), Cursor.encode(1) }) {
			assertThatThrownBy(() -> Cursor.decode(bad, 2)).as(bad).isInstanceOf(InvalidCursorException.class);
		}
	}

}
