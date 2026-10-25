package dev.elsepiol.shop.ping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.elsepiol.shop.support.WebSliceSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A "slice" test: starts only the web layer (controllers, JSON conversion),
 * not the whole application. Fast, and it exercises the real request path
 * without opening a network port.
 */
@WebMvcTest(PingController.class)
@Import(WebSliceSecurityConfig.class)
class PingControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void pingReturnsOkAndServiceName() throws Exception {
		mockMvc.perform(get("/api/v1/ping"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.status").value("ok"))
				.andExpect(jsonPath("$.service").value("shop"));
	}

}
