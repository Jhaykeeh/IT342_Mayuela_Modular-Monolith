package edu.cit.mayuela.inventory;

import edu.cit.mayuela.supplier.SupplierOrderDeliveredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for supplier deliveries and restocks the product. The Inventory
 * module only reacts to the published domain event and never calls the
 * supplier module directly.
 */
@Component
public class InventoryDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryDeliveryListener.class);

    private final InventoryService inventoryService;

    public InventoryDeliveryListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @EventListener
    public void onDelivered(SupplierOrderDeliveredEvent event) {
        boolean restocked = inventoryService.restock(event.productId(), event.units());
        if (restocked) {
            log.info("Delivery restocked {} units of {} (supplier order {})",
                    event.units(), event.productId(), event.poNumber());
        } else {
            log.warn("Delivery received but product {} not found: {} units ({}), check supplier_orders",
                    event.productId(), event.units(), event.poNumber());
        }
    }
}