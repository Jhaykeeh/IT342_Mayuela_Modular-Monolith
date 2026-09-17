package edu.cit.mayuela.shop;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "http://localhost:5173")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderService.OrderResult> createOrder(@RequestBody OrderRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        OrderService.OrderResult result = orderService.placeOrder(request.items());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Void> cancelOrder(@PathVariable Long orderId) {
        try {
            orderService.cancelOrder(orderId);
            return ResponseEntity.ok().build();
        } catch (OrderService.OrderNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (OrderService.OrderAlreadyCancelledException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @GetMapping
    public ResponseEntity<List<Order>> getOrders() {
        return ResponseEntity.ok(orderService.getOrders());
    }

    public record OrderRequest(List<OrderService.OrderItemRequest> items) {
    }
}