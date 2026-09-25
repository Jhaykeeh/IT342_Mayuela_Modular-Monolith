package edu.cit.mayuela.shop;

import edu.cit.mayuela.inventory.Inventory;
import edu.cit.mayuela.inventory.InventoryService;
import edu.cit.mayuela.supplier.SupplierGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public static final int LOW_STOCK_THRESHOLD = 5;

    /** Units to reorder when stock drops below the threshold. */
    public static final int REORDER_UNITS = 30;

    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SupplierGateway supplierGateway;

    public OrderService(InventoryService inventoryService,
                        OrderRepository orderRepository,
                        ApplicationEventPublisher eventPublisher,
                        SupplierGateway supplierGateway) {
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.supplierGateway = supplierGateway;
    }

    /**
     * Places a multi-item order.
     *
     * All line items are validated against current stock BEFORE anything is
     * reserved. If any single item exceeds available stock, the whole order is
     * rejected and no stock is touched. Only after every item passes validation
     * does the order call InventoryService.reserve() for each item.
     */
    @Transactional
    public OrderResult placeOrder(List<OrderItemRequest> items) {
        List<ItemOutcome> outcomes = new ArrayList<>();

        // Phase 1: validate every line item without mutating anything.
        for (OrderItemRequest request : items) {
            Optional<Inventory> optional = inventoryService.getItem(request.productId());
            String outcome;
            if (optional.isEmpty()) {
                outcome = "NOT_FOUND";
            } else if (optional.get().getStock() < request.quantity()) {
                outcome = "INSUFFICIENT_STOCK";
            } else {
                outcome = "RESERVED";
            }
            outcomes.add(new ItemOutcome(request.productId(), outcome));
        }

        // If any item failed, reject the whole order without reserving anything.
        boolean anyFailed = outcomes.stream()
                .anyMatch(o -> !"RESERVED".equals(o.outcome()));
        if (anyFailed) {
            ItemOutcome failed = outcomes.stream()
                    .filter(o -> !"RESERVED".equals(o.outcome()))
                    .findFirst().orElseThrow();
            Inventory affected = inventoryService.getItem(failed.productId()).orElse(null);
            String reason = affected == null
                    ? "Product not found: " + failed.productId()
                    : "Insufficient stock for " + failed.productId()
                            + ". Available: " + affected.getStock();
            return rejectOrder(items, outcomes, reason);
        }

        // Phase 2: reserve every item (all passed validation).
        Order order = new Order("CONFIRMED", null);
        for (OrderItemRequest request : items) {
            boolean reserved = inventoryService.reserve(request.productId(), request.quantity());
            if (!reserved) {
                throw new IllegalStateException("Reserve failed unexpectedly for " + request.productId());
            }
            order.getItems().add(new OrderItem(order, request.productId(), request.quantity()));
        }
        orderRepository.save(order);

        // Phase 3: capture post-reserve stock and apply the low-stock rule.
        List<Inventory> affected = new ArrayList<>();
        List<String> lowStockProducts = new ArrayList<>();
        for (OrderItemRequest request : items) {
            Inventory updated = inventoryService.getItem(request.productId()).orElseThrow();
            affected.add(updated);
            if (updated.getStock() < LOW_STOCK_THRESHOLD) {
                lowStockProducts.add(updated.getProductId());
                eventPublisher.publishEvent(new LowStockEvent(updated.getProductId(), updated.getStock()));
            }
        }

        // The reorder is placed after the order transaction commits, so a
        // rolled-back order can never leave an orphan purchase order behind.
        triggerReorderAfterCommit(lowStockProducts);

        eventPublisher.publishEvent(new OrderConfirmedEvent(order.getOrderId()));
        return new OrderResult("CONFIRMED", null, outcomes, affected);
    }

    /**
     * Calls the supplier gateway, but only once the surrounding transaction has
     * committed. The gateway is idempotent per product, so a sweep job running
     * at the same time cannot produce a duplicate reorder.
     */
    private void triggerReorderAfterCommit(List<String> productIds) {
        if (productIds.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    placeReorders(productIds);
                }
            });
        } else {
            placeReorders(productIds);
        }
    }

    private void placeReorders(List<String> productIds) {
        for (String productId : productIds) {
            try {
                supplierGateway.placeReorder(productId, REORDER_UNITS);
            } catch (RuntimeException e) {
                log.warn("Reorder for " + productId + " failed: " + e.getMessage());
            }
        }
    }

    private OrderResult rejectOrder(List<OrderItemRequest> requests,
                                    List<ItemOutcome> outcomes,
                                    String reason) {
        Order rejected = new Order("REJECTED", reason);
        for (OrderItemRequest request : requests) {
            rejected.getItems().add(new OrderItem(rejected, request.productId(), request.quantity()));
        }
        orderRepository.save(rejected);
        eventPublisher.publishEvent(new OrderRejectedEvent(rejected.getOrderId(), reason));
        return new OrderResult("REJECTED", reason, outcomes, new ArrayList<>());
    }

    /**
     * Cancels an order and returns every reserved line item to stock.
     * Throws so callers translate to 404/409.
     */
    @Transactional
    public void cancelOrder(Long orderId) {
        Optional<Order> optional = orderRepository.findById(orderId);
        if (optional.isEmpty()) {
            throw new OrderNotFoundException(orderId);
        }
        Order order = optional.get();
        if ("CANCELLED".equals(order.getStatus())) {
            throw new OrderAlreadyCancelledException(orderId);
        }
        if ("CONFIRMED".equals(order.getStatus())) {
            for (OrderItem item : order.getItems()) {
                inventoryService.restock(item.getProductId(), item.getQuantity());
            }
        }
        order.setStatus("CANCELLED");
        order.setReason("Cancelled by user");
        orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public List<Order> getOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    public record OrderItemRequest(String productId, int quantity) {
    }

    public record ItemOutcome(String productId, String outcome) {
    }

    public record OrderResult(String status,
                              String reason,
                              List<ItemOutcome> items,
                              List<Inventory> inventory) {
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(Long orderId) {
            super("Order not found: " + orderId);
        }
    }

    public static class OrderAlreadyCancelledException extends RuntimeException {
        public OrderAlreadyCancelledException(Long orderId) {
            super("Order already cancelled: " + orderId);
        }
    }
}