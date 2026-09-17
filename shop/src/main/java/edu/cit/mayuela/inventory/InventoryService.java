package edu.cit.mayuela.inventory;

import java.util.List;
import java.util.Optional;

public interface InventoryService {

    Optional<Inventory> getItem(String productId);

    /**
     * Attempts to reserve the given quantity for the product.
     * Returns true if reserved, false if stock is insufficient.
     */
    boolean reserve(String productId, int quantity);

    /**
     * Returns the given quantity back to stock for the product.
     * Returns true if restocked, false if the product does not exist.
     */
    boolean restock(String productId, int quantity);

    /** Returns all products with their current stock. */
    List<Inventory> getAllItems();
}