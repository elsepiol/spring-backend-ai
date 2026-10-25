package dev.elsepiol.shop.inventory;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/inventory")
public class AdminInventoryController {

	private final InventoryService service;

	public AdminInventoryController(InventoryService service) {
		this.service = service;
	}

	@GetMapping("/low-stock")
	public List<LowStockItem> lowStock(@RequestParam(defaultValue = "5") int threshold) {
		return service.lowStock(threshold);
	}

}
