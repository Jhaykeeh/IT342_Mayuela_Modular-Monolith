package edu.cit.mayuela.shop;

import edu.cit.mayuela.inventory.Inventory;
import edu.cit.mayuela.inventory.InventoryService;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;

    public OrderService(InventoryService inventoryService, OrderRepository orderRepository) {
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
    }

    public OrderResult placeOrder(String productId, int quantity) {
        Optional<Inventory> item = inventoryService.getItem(productId);
        if (item.isEmpty()) {
            Order rejected = new Order(productId, quantity, "REJECTED", "Product not found");
            orderRepository.save(rejected);
            return new OrderResult("REJECTED", "Product not found", null);
        }

        boolean reserved = inventoryService.reserve(productId, quantity);
        Inventory updatedItem = inventoryService.getItem(productId).orElseThrow();

        if (!reserved) {
            Order rejected = new Order(productId, quantity, "REJECTED",
                    "Insufficient stock. Available: " + updatedItem.getStock());
            orderRepository.save(rejected);
            return new OrderResult("REJECTED",
                    "Insufficient stock. Available: " + updatedItem.getStock(), updatedItem);
        }

        Order confirmed = new Order(productId, quantity, "CONFIRMED", null);
        orderRepository.save(confirmed);
        return new OrderResult("CONFIRMED", null, updatedItem);
    }

    public record OrderResult(String status, String reason, Inventory inventory) {
    }
}
