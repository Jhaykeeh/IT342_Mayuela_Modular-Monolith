package edu.cit.mayuela.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single shared logger name for the whole supplier adapter so every
 * LegacySupply-related line can be grepped in one place when reviewing
 * evidence.
 */
final class SupplierLogger {

    private SupplierLogger() {
    }

    static Logger get() {
        return LoggerFactory.getLogger("supplier.legacysupply");
    }
}