package dev.elsepiol.shop.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.elsepiol.shop.ShopApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Level 6: the app is a MODULAR monolith. Each package directly under dev.elsepiol.shop
 * (auth, product, order, outbox...) is a module, and Spring Modulith reads the compiled classes
 * to check the rules that would let us split one out into its own service later:
 *
 *   - no cycles: if order uses product and product uses order, neither can ever leave alone;
 *   - modules only use each other's public API, never internals in sub-packages.
 *
 * No Spring context, no database: this is static analysis of bytecode, it runs in a second.
 */
class ModularityTest {

	private final ApplicationModules modules = ApplicationModules.of(ShopApplication.class);

	@Test
	void theModulesHaveNoCyclesAndOnlyUseEachOthersPublicApi() {
		modules.verify();
	}

	@Test
	void productDependsOnNothingElseSoItCouldBeTheFirstToMoveOut() {
		ApplicationModule product = modules.getModuleByName("product").orElseThrow();

		assertThat(product.getBootstrapDependencies(modules)).isEmpty();
		assertThat(product.getDirectDependencies(modules).stream().toList()).isEmpty();
	}

	/** Writes a diagram of the modules (PlantUML) and a short description of each, to target/spring-modulith-docs. */
	@Test
	void documentTheModules() {
		new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml().writeModuleCanvases();
	}

}
