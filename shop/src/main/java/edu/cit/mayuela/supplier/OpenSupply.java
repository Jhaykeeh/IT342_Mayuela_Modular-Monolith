package edu.cit.mayuela.supplier;

/**
 * The supplier module's answer to one question: "if something needs units we do
 * not have, is more already on its way?"
 *
 * This is deliberately the smallest possible surface the Tiangge channel needs.
 * It is expressed in the application's own terms (a product id and a number of
 * units) and hides purchase orders, cases, pack sizes and supplier status codes,
 * so the channel never learns a LegacySupply concept.
 *
 * {@link SupplierGateway} stays the richer interface for the Order module's
 * low-stock rule; this one exists for a caller that only needs a yes/no answer
 * to decide between backordering and rejecting an order.
 */
public interface OpenSupply {

    /**
     * Makes sure the shortage for a product is covered by a purchase order that
     * is still open at LegacySupply, placing one through the adapter if not.
     *
     * Idempotent: while a reorder for the product is open the existing one is
     * reused, so repeated calls for the same shortage cannot create a second
     * purchase order.
     *
     * @param productId  inventory product that is short
     * @param missingQty units still needed
     * @return true when units are covered by an open purchase order (existing
     *         or just placed), false when nothing is coming: no supplier mapping,
     *         or the supplier refused the reorder permanently.
     */
    boolean ensureCovered(String productId, int missingQty);
}