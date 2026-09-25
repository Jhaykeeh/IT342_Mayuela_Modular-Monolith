package edu.cit.mayuela.supplier;

/**
 * Domain event published once when a supplier purchase order is delivered.
 * Inventory listens for this event and restocks {@link #units()}; neither the
 * Order nor the Inventory module ever calls the supplier module directly in
 * response to a delivery.
 *
 * @param supplierOrderId local supplier order id
 * @param productId       inventory product to restock
 * @param units           units received, expressed in this application's unit
 * @param poNumber        supplier purchase order number
 */
public record SupplierOrderDeliveredEvent(Long supplierOrderId,
                                          String productId,
                                          int units,
                                          String poNumber) {
}
