package edu.cit.mayuela.supplier;

import org.slf4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Reuses an open reorder when there is one and places a new one otherwise, so a
 * backorder can be promised only when goods are genuinely on their way.
 *
 * The decision is made entirely from this module's own status vocabulary: a
 * purchase order in PENDING, ACCEPTED, PICKING or SHIPPED counts as coming,
 * anything terminal (DELIVERED, FAILED, UNKNOWN) does not. A reorder that fails
 * permanently means the shortage will never be filled, which is exactly what
 * the caller needs to hear before it rejects an order.
 */
@Service
class OpenSupplyImpl implements OpenSupply {

    private final SupplierGateway gateway;
    private final Logger log = SupplierLogger.get();

    OpenSupplyImpl(SupplierGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public boolean ensureCovered(String productId, int missingQty) {
        if (gateway.hasOpenReorder(productId)) {
            log.info("Shortage for {} ({} units) is already covered by an open purchase order",
                    productId, missingQty);
            return true;
        }
        ReorderResult placed = gateway.placeReorder(productId, Math.max(missingQty, 1));
        boolean covered = placed != null && placed.status() != null && placed.status().isOpen();
        if (covered) {
            log.info("Placed purchase order {} ({}) to cover {} missing units of {}",
                    placed.buyerRef(), placed.status(), missingQty, productId);
        } else {
            log.warn("No restock coming for {} ({} units missing): {}",
                    productId, missingQty, placed == null ? "no response" : placed.message());
        }
        return covered;
    }
}