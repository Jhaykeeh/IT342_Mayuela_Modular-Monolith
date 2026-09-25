package edu.cit.mayuela.supplier;

/**
 * Result of asking the supplier module to place (or find) a reorder.
 * Everything here is expressed in this application's own terms: no supplier
 * item numbers, no unit-of-measure codes, no supplier status codes.
 *
 * @param supplierOrderId local id of the supplier_orders row
 * @param productId       the inventory product this reorder is for
 * @param status          local status of the reorder
 * @param units           units needed, in this application's unit
 * @param cases           whole cases actually requested from the supplier
 * @param buyerRef        unique reference for this reorder ("RO-" + id)
 * @param requestId       the X-Request-Id this reorder is sent with; stable
 *                        across retries and restarts
 * @param poNumber        supplier purchase order number, if one exists yet
 * @param message         human readable note (why it failed, etc.)
 */
public record ReorderResult(Long supplierOrderId,
                            String productId,
                            ReorderStatus status,
                            int units,
                            int cases,
                            String buyerRef,
                            String requestId,
                            String poNumber,
                            String message) {
}
