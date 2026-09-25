package edu.cit.mayuela.supplier;

/**
 * Status of a reorder as this system understands it. Deliberately expressed in
 * this application's own vocabulary: LegacySupply numeric status codes
 * (10/20/30/40) never appear outside the supplier adapter.
 *
 * PENDING    - recorded locally, not yet confirmed by the supplier
 * ACCEPTED   - the supplier has acknowledged the purchase order
 * PICKING    - the supplier is preparing the order
 * SHIPPED    - the supplier has dispatched the order
 * DELIVERED  - terminal: goods received, stock may be restocked
 * FAILED     - terminal: the supplier refused the order permanently
 * UNKNOWN    - terminal: the supplier returned a status this system does not
 *              recognise; kept visible for manual review, never restocked
 */
public enum ReorderStatus {
    PENDING,
    ACCEPTED,
    PICKING,
    SHIPPED,
    DELIVERED,
    FAILED,
    UNKNOWN;

    public boolean isOpen() {
        return this == PENDING || this == ACCEPTED || this == PICKING || this == SHIPPED;
    }
}
