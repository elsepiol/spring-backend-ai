package dev.elsepiol.shop.greeting;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REFERENCE SOLUTION for Exercise 0. Try the exercise yourself before reading this.
 */
@RestController
@RequestMapping("/api/v1/greetings")
public class GreetingController {

	@GetMapping("/{name}")
	public GreetingResponse greet(
			@PathVariable @Size(min = 2, max = 30) @Pattern(regexp = "[A-Za-z]+") String name) {
		return new GreetingResponse("Hello, " + name + "!");
	}

	public record GreetingResponse(String message) {
	}

}
