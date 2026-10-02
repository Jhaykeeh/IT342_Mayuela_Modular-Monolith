package edu.cit.mayuela.channel;

import edu.cit.mayuela.inventory.Inventory;
import edu.cit.mayuela.inventory.InventoryService;
import edu.cit.mayuela.inventory.StockChanged;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps the marketplace's view of stock current.
 *
 * Driven entirely by events, never by a timer: the Inventory module announces
 * every movement with {@link StockChanged} and this class reacts after that
 * transaction has committed. The listener is AFTER_COMMIT so a quantity is only
 * ever published once it is really persisted, and {@code fallbackExecution}
 * covers a mutation that happened without a transaction.
 *
 * Two problems that a plain event listener would have:
 *
 *  - Ordering. Tiangge must be told the outcome of an order before it is told
 *    the new stock, otherwise the marketplace could sell stock that is already
 *    committed to a customer. {@link #hold()} and {@link #release()} bracket the
 *    processing of a marketplace order: while anything is held, nothing is
 *    published, and the flush happens the moment the last hold is released.
 *
 *  - Blocking. Publishing is HTTP, and the caller is usually a thread that is
 *    committing a database transaction. All publishing therefore happens on a
 *    single background thread, which also means Tiangge sees stock updates in
 *    the order they happened.
 *
 * A failed publish puts its SKUs back into the dirty set, and the five second
 * sweeper is the only thing that retries them.
 */
@Component
class StockSync {

    private final InventoryService inventory;
    private final TianggeClient client;
    private final ChannelProperties properties;
    private final ChannelStatus status;
    private final Logger log = ChannelLogger.get();

    private final Set<String> dirty = new LinkedHashSet<>();

    private final AtomicInteger holds = new AtomicInteger();

    private final Object lock = new Object();

    private final ExecutorService publisher = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "channel-stock-publisher");
        thread.setDaemon(true);
        return thread;
    });

    StockSync(InventoryService inventory,
              TianggeClient client,
              ChannelProperties properties,
              ChannelStatus status) {
        this.inventory = inventory;
        this.client = client;
        this.properties = properties;
        this.status = status;
    }

    /**
     * Marks the moment a marketplace order starts being processed.
     *
     * Stock is not published while any hold is outstanding, so the marketplace
     * cannot learn about the new stock before it learns the decision.
     */
    void hold() {
        holds.incrementAndGet();
    }

    /** Ends a hold; the last release flushes everything that changed. */
    void release() {
        int remaining = holds.decrementAndGet();
        if (remaining < 0) {
            holds.compareAndSet(remaining, 0);
            remaining = 0;
        }
        if (remaining == 0) {
            flush();
        }
    }

    /**
     * The Inventory module's own signal. Runs after the stock movement has
     * committed, so the quantity read here is the quantity that is stored.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onStockChanged(StockChanged event) {
        markDirty(event.sku());
    }

    /** Queues a product whose stock moved. Coalesces repeated movements. */
    void markDirty(String sku) {
        if (sku == null || !isListed(sku)) {
            return;
        }
        synchronized (lock) {
            dirty.add(sku);
        }
        if (holds.get() == 0) {
            flush();
        }
    }

    /** Queues every listed product; used for the first publish after start-up. */
    void markEveryListedSkuDirty() {
        for (ChannelProperties.Listing listing : properties.getListings()) {
            synchronized (lock) {
                dirty.add(listing.getSellerSku());
            }
        }
        flush();
    }

    /** Retries anything that failed earlier. The only periodic work here. */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    void retryFailedPublishes() {
        if (!status.online() || holds.get() > 0) {
            return;
        }
        synchronized (lock) {
            if (!dirty.isEmpty()) {
                flushLocked();
            }
        }
    }

    private void flush() {
        synchronized (lock) {
            if (holds.get() > 0) {
                return;
            }
            flushLocked();
        }
    }

    /** Must be called while holding {@link #lock}. */
    private void flushLocked() {
        if (dirty.isEmpty()) {
            return;
        }
        Set<String> batch = new HashSet<>(dirty);
        dirty.clear();
        try {
            publisher.execute(() -> publish(batch));
        } catch (RejectedExecutionException e) {
            synchronized (lock) {
                dirty.addAll(batch);
            }
            log.warn("Stock publisher unavailable; {} change(s) stay queued", batch.size());
        }
    }

    /** Runs on the single publisher thread; reads the latest stored quantity. */
    private void publish(Set<String> skus) {
        List<TianggeBodies.Stock> payload = new ArrayList<>();
        for (String sku : skus) {
            if (!isListed(sku)) {
                continue;
            }
            int available = inventory.getItem(sku).map(Inventory::getStock).orElse(0);
            payload.add(new TianggeBodies.Stock(sku, available));
        }
        if (payload.isEmpty()) {
            return;
        }
        try {
            client.publishStock(payload);
            log.info("Published stock to Tiangge: {}", payload);
        } catch (RuntimeException e) {
            synchronized (lock) {
                dirty.addAll(skus);
            }
            log.warn("Stock publish failed ({}); {} sku(s) stay queued for retry",
                    e.getMessage(), skus.size());
        }
    }

    /** Only listed products may be published; anything else would be a 422. */
    private boolean isListed(String sku) {
        return properties.getListings().stream()
                .anyMatch(listing -> sku.equals(listing.getSellerSku()));
    }
}