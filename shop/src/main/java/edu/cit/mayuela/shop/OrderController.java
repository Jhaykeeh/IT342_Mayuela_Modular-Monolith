package edu.cit.mayuela.shop;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
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
        OrderService.OrderResult result = orderService.placeOrder(request.productId(), request.quantity());
        return ResponseEntity.ok(result);
    }

    public record OrderRequest(String productId, int quantity) {
    }
}
