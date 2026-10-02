package edu.cit.mayuela.inventory;

/**
 * Generic fact: the available quantity of one product changed.
 *
 * The Inventory module publishes this after every mutation and knows nothing
 * about who is listening. It is the only signal the Tiangge channel uses to keep
 * the marketplace's view of stock current, so the event deliberately carries no
 * channel, marketplace or supplier concepts.
 *
 * @param sku    the product whose available quantity changed
 * @param reason what kind of movement caused it
 */
public record StockChanged(String sku, Reason reason) {

    public enum Reason {

        /** Stock was taken out for an order. */
        RESERVED,

        /** Reserved stock went back: cancellation, compensating release, restock. */
        RELEASED,

        /** Stock arrived from a supplier delivery. */
        DELIVERY,

        /** Any other manual or system correction. */
        ADJUSTED
    }
}