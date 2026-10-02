package edu.cit.mayuela.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All stock movements go through here.
 *
 * Two properties matter for the rest of the system:
 *
 *  1. Reservation is atomic. {@link InventoryRepository#takeIfAvailable} issues a
 *     single conditional UPDATE, so the guard cannot be invalidated between the
 *     check and the write. Callers that need several lines reserved together
 *     (OrderService) still get all-or-nothing semantics by releasing whatever
 *     they already took when one line fails.
 *
 *  2. Every mutation announces itself with {@link StockChanged}. The event is
 *     published inside the surrounding transaction, so listeners that ask for
 *     AFTER_COMMIT only ever see quantities that really persisted.
 */
@Service
class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    InventoryServiceImpl(InventoryRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Optional<Inventory> getItem(String productId) {
        return repository.findById(productId);
    }

    @Override
    @Transactional
    public boolean reserve(String productId, int quantity) {
        if (quantity <= 0 || productId == null) {
            return false;
        }
        if (repository.takeIfAvailable(productId, quantity) == 0) {
            return false;
        }
        eventPublisher.publishEvent(new StockChanged(productId, StockChanged.Reason.RESERVED));
        return true;
    }

    @Override
    @Transactional
    public boolean restock(String productId, int quantity) {
        return increase(productId, quantity, StockChanged.Reason.RELEASED);
    }

    @Override
    @Transactional
    public boolean deliver(String productId, int quantity) {
        return increase(productId, quantity, StockChanged.Reason.DELIVERY);
    }

    @Override
    public List<Inventory> getAllItems() {
        return repository.findAll();
    }

    private boolean increase(String productId, int quantity, StockChanged.Reason reason) {
        if (quantity <= 0 || productId == null) {
            return false;
        }
        if (repository.add(productId, quantity) == 0) {
            return false;
        }
        eventPublisher.publishEvent(new StockChanged(productId, reason));
        return true;
    }
}