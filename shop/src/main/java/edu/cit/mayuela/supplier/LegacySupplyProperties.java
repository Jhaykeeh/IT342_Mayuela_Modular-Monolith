package edu.cit.mayuela.supplier;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the LegacySupply adapter. The API key is deliberately read
 * from the LS_API_KEY environment variable and never committed to Git.
 */
@ConfigurationProperties(prefix = "legacysupply")
class LegacySupplyProperties {

    private String baseUrl = "https://legacysupply.onrender.com/api/v1";

    private String clientId;

    private String apiKey = "";

    /** productId -> catalog entry describing the supplier item. */
    private Map<String, CatalogEntry> catalog = new HashMap<>();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Map<String, CatalogEntry> getCatalog() {
        return catalog;
    }

    public void setCatalog(Map<String, CatalogEntry> catalog) {
        this.catalog = catalog;
    }

    public static class CatalogEntry {

        private String sku = "";

        private int packSize = 1;

        public String getSku() {
            return sku;
        }

        public void setSku(String sku) {
            this.sku = sku;
        }

        public int getPackSize() {
            return packSize;
        }

        public void setPackSize(int packSize) {
            this.packSize = packSize;
        }
    }
}