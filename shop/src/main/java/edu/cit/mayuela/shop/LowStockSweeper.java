package edu.cit.mayuela.shop;

import edu.cit.mayuela.inventory.Inventory;
import edu.cit.mayuela.inventory.InventoryService;
import edu.cit.mayuela.supplier.SupplierGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for the low-stock rule: every minute it checks every product and
 * calls the supplier gateway for anything still below the threshold that has
 * no open reorder. This guarantees a reorder for products that were already
 * low before any order touched them, and that no reorder is ever silently
 * dropped if the synchronous path is skipped for any reason.
 */
@Component
public class LowStockSweeper {

    private static final Logger log = LoggerFactory.getLogger(LowStockSweeper.class);

    private final InventoryService inventoryService;
    private final SupplierGateway supplierGateway;

    public LowStockSweeper(InventoryService inventoryService, SupplierGateway supplierGateway) {
        this.inventoryService = inventoryService;
        this.supplierGateway = supplierGateway;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void sweepBelowThresholdProducts() {
        for (Inventory item : inventoryService.getAllItems()) {
            if (item.getStock() >= OrderService.LOW_STOCK_THRESHOLD) {
                continue;
            }
            if (supplierGateway.hasOpenReorder(item.getProductId())) {
                continue;
            }
            log.info("Sweep: reordering " + item.getProductId() + " (stock " + item.getStock()
                    + " below threshold)");
            supplierGateway.placeReorder(item.getProductId(), OrderService.REORDER_UNITS);
        }
    }
}