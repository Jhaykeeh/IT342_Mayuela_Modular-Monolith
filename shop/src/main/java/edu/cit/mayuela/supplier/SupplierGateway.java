package edu.cit.mayuela.supplier;

import java.util.List;

/**
 * The only entry point other modules may use to talk to the supplier.
 *
 * The gateway takes this application's own terms (an inventory product id and
 * a number of units) and returns this application's own result type. All
 * translation to and from LegacySupply - XML, sessions, supplier item numbers,
 * pack sizes, unit conversion, status codes - happens behind this interface.
 *
 * The implementation is idempotent per product: while a reorder for a product
 * is still open, further calls return that same reorder instead of creating a
 * second one, so a low-stock rule firing twice cannot produce two purchase
 * orders.
 */
public interface SupplierGateway {

    /**
     * Places (or re-finds) the reorder for the given product, converting the
     * requested units into whole supplier cases, rounding up.
     *
     * Never blocks the caller on an unavailable supplier: if the supplier
     * cannot be reached the reorder stays PENDING locally and a scheduled job
     * retries it later with the same X-Request-Id.
     */
    ReorderResult placeReorder(String productId, int units);

    /** True while a reorder for this product is still open (PENDING to SHIPPED). */
    boolean hasOpenReorder(String productId);

    /** Snapshot of every reorder this system currently knows about. */
    List<ReorderResult> listReorders();
}
