package edu.cit.mayuela.supplier;

/**
 * Translates LegacySupply numeric status codes into this system's
 * {@link ReorderStatus}. LegacySupply codes never leave this package.
 *
 * 10 Accepted, 20 Picking, 30 Shipped, 40 Delivered. Anything else is mapped
 * to UNKNOWN and handled as an unexpected status by the polling job.
 */
final class StatusMapper {

    private StatusMapper() {
    }

    static ReorderStatus fromLegacyCode(int statusCode) {
        switch (statusCode) {
            case 10:
                return ReorderStatus.ACCEPTED;
            case 20:
                return ReorderStatus.PICKING;
            case 30:
                return ReorderStatus.SHIPPED;
            case 40:
                return ReorderStatus.DELIVERED;
            default:
                return ReorderStatus.UNKNOWN;
        }
    }
}