package edu.cit.mayuela.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface InventoryRepository extends JpaRepository<Inventory, String> {

    /**
     * Atomic reservation: the guard and the decrement live in one statement, so
     * the database itself decides the winner. No read-then-write window exists,
     * which is what stops a UI order and a marketplace order from overselling
     * each other during a flash sale.
     *
     * @return 1 when the units were taken, 0 when stock was insufficient.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Inventory i set i.stock = i.stock - :qty "
            + "where i.productId = :sku and i.stock >= :qty")
    int takeIfAvailable(String sku, int qty);

    /** Atomic increment; returns 0 when the product does not exist. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Inventory i set i.stock = i.stock + :qty where i.productId = :sku")
    int add(String sku, int qty);
}