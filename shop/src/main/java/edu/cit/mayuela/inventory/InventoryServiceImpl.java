package edu.cit.mayuela.inventory;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository repository;

    InventoryServiceImpl(InventoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Inventory> getItem(String productId) {
        return repository.findById(productId);
    }

    @Override
    @Transactional
    public boolean reserve(String productId, int quantity) {
        Optional<Inventory> optional = repository.findById(productId);
        if (optional.isEmpty()) {
            return false;
        }
        Inventory item = optional.get();
        if (item.getStock() < quantity) {
            return false;
        }
        item.setStock(item.getStock() - quantity);
        repository.save(item);
        return true;
    }
}
