package edu.cit.mayuela.supplier;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adds the database-level guard that makes a duplicate reorder for the same
 * product impossible even under concurrency or after a restart: a partial
 * unique index on supplier_orders(product_id) while the order is still open.
 */
@Component
class SupplierSchemaInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final org.slf4j.Logger log = SupplierLogger.get();

    SupplierSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_orders_open_product "
                + "ON supplier_orders(product_id) "
                + "WHERE status IN ('PENDING','ACCEPTED','PICKING','SHIPPED')");
        log.info("Guaranteed partial uniqueness for open reorders per product");
    }
}