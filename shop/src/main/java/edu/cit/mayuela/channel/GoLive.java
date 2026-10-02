package edu.cit.mayuela.channel;

import edu.cit.mayuela.platform.AppInstance;
import java.util.List;
import org.slf4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brings the channel up, and keeps bringing it up until Tiangge answers.
 *
 * The order of the three calls in {@link #goLive()} is a requirement, not a
 * preference: the first call this application ever makes to Tiangge has to be
 * the heartbeat, so the marketplace learns the instance exists before it is
 * asked to do any work.
 *
 * Tiangge being unavailable at boot is treated as an ordinary condition. Nothing
 * here throws out of the scheduler: the sequence is simply retried every five
 * seconds, and until it succeeds the application still serves the React UI and
 * still runs the Lab 3 supplier machinery. That is what makes an unattended
 * start safe.
 */
@Component
class GoLive {

    private final ChannelProperties properties;
    private final ChannelStatusImpl status;
    private final TianggeClient client;
    private final StockSync stockSync;
    private final Logger log = ChannelLogger.get();

    GoLive(ChannelProperties properties,
           ChannelStatusImpl status,
           TianggeClient client,
           StockSync stockSync) {
        this.properties = properties;
        this.status = status;
        this.client = client;
        this.stockSync = stockSync;
    }

    /** Logs the evidence the lab asks for, before anything is attempted. */
    @EventListener(ApplicationReadyEvent.class)
    void announceInstance() {
        log.info("Tiangge channel starting; instanceId={} startedAt={}",
                AppInstance.instanceId(), AppInstance.startedAt());
    }

    /**
     * Heartbeat, then listings, then the first stock publish. Idempotent: once
     * online it does nothing, and until it succeeds it starts again from the
     * heartbeat.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 1_000)
    void goLive() {
        if (status.online()) {
            return;
        }
        try {
            client.heartbeat(properties.getAppName());
            client.replaceListings(properties.getListings());
            log.info("Heartbeat accepted and {} listing(s) published: {}",
                    properties.getListings().size(), sellerSkus());

            status.markOnline();

            // Only now is the marketplace told what stock exists.
            stockSync.markEveryListedSkuDirty();
            log.info("First stock publish queued; channel is live");
        } catch (RuntimeException e) {
            log.warn("Tiangge not usable yet ({}); retrying the whole start-up sequence in 5s",
                    e.getMessage());
        }
    }

    private List<String> sellerSkus() {
        return properties.getListings().stream()
                .map(ChannelProperties.Listing::getSellerSku)
                .toList();
    }
}