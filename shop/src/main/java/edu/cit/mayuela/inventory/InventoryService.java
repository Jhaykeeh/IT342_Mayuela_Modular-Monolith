package edu.cit.mayuela.inventory;

import java.util.List;
import java.util.Optional;

public interface InventoryService {

    Optional<Inventory> getItem(String productId);

    /**
     * Attempts to reserve the given quantity for the product.
     *
     * The check and the decrement are a single atomic database statement, so two
     * concurrent callers (the React UI and a marketplace order) can never both
     * succeed for the same units and stock can never go negative.
     *
     * @return true if reserved, false if stock is insufficient or unknown.
     */
    boolean reserve(String productId, int quantity);

    /**
     * Returns the given quantity back to stock for the product.
     * Returns true if restocked, false if the product does not exist.
     */
    boolean restock(String productId, int quantity);

    /**
     * Adds stock that physically arrived from a supplier delivery.
     * Same arithmetic as {@link #restock} but reported under a different reason
     * so downstream listeners can tell a delivery apart from a cancellation.
     *
     * @return true if restocked, false if the product does not exist.
     */
    boolean deliver(String productId, int quantity);

    /** Returns all products with their current stock. */
    List<Inventory> getAllItems();
}