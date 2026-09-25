package edu.cit.mayuela.supplier;

/**
 * The supplier-side item a product maps to. This record belongs to the
 * supplier adapter: its fields (SKU, pack size) are LegacySupply concepts that
 * must never leak into the Order or Inventory modules.
 */
record SupplierItem(String sku, int packSize) {
}