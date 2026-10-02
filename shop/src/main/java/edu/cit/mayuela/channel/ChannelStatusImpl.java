package edu.cit.mayuela.channel;

import edu.cit.mayuela.platform.AppInstance;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Holds the two facts every other channel class needs to make a decision: which
 * instance we are, and whether the marketplace is currently reachable.
 *
 * "Online" means the full start-up sequence has completed - heartbeat, listings
 * and the first stock publish. Everything time-based (poller, heartbeat, stock
 * sweeper, backorder retry) consults it, which is what keeps a marketplace that
 * is down at boot from producing a stream of failed calls.
 */
@Component
class ChannelStatusImpl implements ChannelStatus {

    private final AtomicBoolean online = new AtomicBoolean(false);
    private final ChannelStore store;
    private final Logger log = ChannelLogger.get();

    ChannelStatusImpl(ChannelStore store) {
        this.store = store;
    }

    @Override
    public String instanceId() {
        return AppInstance.instanceId();
    }

    @Override
    public long feedCursor() {
        return store.readCursor();
    }

    @Override
    public boolean online() {
        return online.get();
    }

    /** Called once the start-up sequence has fully succeeded. */
    void markOnline() {
        if (online.compareAndSet(false, true)) {
            log.info("Channel is online; resuming feed from cursor {}", readQuietly());
        }
    }

    /** Cursor read that never throws, for logging during start-up. */
    private long readQuietly() {
        try {
            return store.readCursor();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}