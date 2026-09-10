package edu.cit.mayuela.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

interface InventoryRepository extends JpaRepository<Inventory, String> {
}
