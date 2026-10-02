package edu.cit.mayuela.channel;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the Tiangge channel needs to reach the marketplace.
 *
 * Package-private on purpose: nothing outside this package may know how Tiangge
 * is configured. The credentials are never committed - they come from the
 * {@code TIANGGE_CLIENT_ID} and {@code LS_API_KEY} environment variables.
 *
 * The listings duplicate the Lab 3 supplier mapping on purpose. The channel
 * must be able to build a Tiangge listing without knowing anything about the
 * supplier adapter's internals, and a listing is exactly the pair
 * (my product, supplier item) plus a title.
 */
@ConfigurationProperties(prefix = "channel")
class ChannelProperties {

    /** Root of the Tiangge API. */
    private String baseUrl = "https://legacysupply.onrender.com/tiangge/v1";

    /** Sent as the X-Client-Id header (the student id). */
    private String clientId = "";

    /** Sent as "Authorization: Bearer ...". Never committed. */
    private String apiKey = "";

    /** Reported in the heartbeat. */
    private String appName = "shop";

    /** Published as Tiangge listings on startup; between 1 and 10 entries. */
    private List<Listing> listings = new ArrayList<>();

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

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public List<Listing> getListings() {
        return listings;
    }

    public void setListings(List<Listing> listings) {
        this.listings = listings;
    }

    /**
     * One product offered on the marketplace.
     *
     * @param sellerSku   this application's own product id (P100, ...)
     * @param title       what the marketplace shows
     * @param supplierSku the LegacySupply item behind it (NRQ-3766, ...)
     */
    static class Listing {

        private String sellerSku = "";

        private String title = "";

        private String supplierSku = "";

        public String getSellerSku() {
            return sellerSku;
        }

        public void setSellerSku(String sellerSku) {
            this.sellerSku = sellerSku;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getSupplierSku() {
            return supplierSku;
        }

        public void setSupplierSku(String supplierSku) {
            this.supplierSku = supplierSku;
        }
    }
}