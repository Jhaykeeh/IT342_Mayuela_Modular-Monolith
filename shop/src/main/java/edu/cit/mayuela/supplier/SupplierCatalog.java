package edu.cit.mayuela.supplier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Holds the mapping between this application's product ids and LegacySupply's
 * items (SupplierSku + PackSize). Loaded from application properties; used by
 * the adapter to convert units into whole cases.
 */
@Component
class SupplierCatalog {

    private final Map<String, SupplierItem> items;

    SupplierCatalog(LegacySupplyProperties properties) {
        this.items = properties.getCatalog().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> new SupplierItem(e.getValue().getSku(), e.getValue().getPackSize())));
    }

    /** Returns the supplier item for a product, or null when not mapped. */
    SupplierItem find(String productId) {
        return items.get(productId);
    }

    /** Converts units needed into whole cases, rounding up, min 1. */
    int casesFor(String productId, int units) {
        SupplierItem item = find(productId);
        if (item == null) {
            throw new IllegalArgumentException("No supplier mapping for product " + productId);
        }
        BigDecimal need = BigDecimal.valueOf(units);
        BigDecimal pack = BigDecimal.valueOf(item.packSize());
        int cases = need.divide(pack, 0, RoundingMode.CEILING).max(BigDecimal.ONE).intValue();
        return Math.min(cases, 99);
    }
}