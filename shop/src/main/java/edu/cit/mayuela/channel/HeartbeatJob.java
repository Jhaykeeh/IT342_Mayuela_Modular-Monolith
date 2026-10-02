package edu.cit.mayuela.channel;

import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps this instance declared alive.
 *
 * Tiangge considers an instance offline after 90 seconds without a heartbeat,
 * so this runs every 30 seconds and gives it a wide margin. It only runs while
 * the channel is online, which means the heartbeat is also the thing that makes
 * the instance live in the first place - {@link GoLive} sends the very first
 * one.
 *
 * A failing heartbeat is never fatal. If Tiangge is slow or restarting, the
 * calls simply fail and the next attempt tries again; stock and the order feed
 * keep flowing through their own retry paths in the meantime.
 */
@Component
class HeartbeatJob {

    private final ChannelProperties properties;
    private final ChannelStatus status;
    private final TianggeClient client;
    private final Logger log = ChannelLogger.get();

    HeartbeatJob(ChannelProperties properties, ChannelStatus status, TianggeClient client) {
        this.properties = properties;
        this.status = status;
        this.client = client;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void beat() {
        if (!status.online()) {
            return;
        }
        try {
            client.heartbeat(properties.getAppName());
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed ({}); staying up and trying again in 30s", e.getMessage());
        }
    }
}