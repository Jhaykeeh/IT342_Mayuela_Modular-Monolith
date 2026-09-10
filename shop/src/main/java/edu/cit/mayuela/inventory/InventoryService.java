package edu.cit.mayuela.inventory;

import java.util.Optional;

public interface InventoryService {

    Optional<Inventory> getItem(String productId);

    /**
     * Attempts to reserve the given quantity for the product.
     * Returns true if reserved, false if stock is insufficient.
     */
    boolean reserve(String productId, int quantity);
}
