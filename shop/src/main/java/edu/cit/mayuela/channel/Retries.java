package edu.cit.mayuela.channel;

import java.util.function.Supplier;

/**
 * Hand-written retry with exponential backoff.
 *
 * The rule is deliberately narrow: only server-side trouble is retried (HTTP 5xx)
 * and transport failures (connect timeouts, read timeouts, dropped
 * connections). Every 4xx is permanent and returned to the caller immediately -
 * retrying a wrong decision or an unknown sku would only produce a wrong answer
 * more slowly.
 *
 * Backoff starts at 300ms and doubles up to 4s, so one call is abandoned after
 * roughly ten seconds of trying. Nothing here sleeps the caller forever, which
 * matters because the feed poller runs on a scheduler thread.
 */
final class Retries {

    private static final int MAX_ATTEMPTS = 5;

    private static final long INITIAL_BACKOFF_MILLIS = 300;

    private static final long MAX_BACKOFF_MILLIS = 4_000;

    private Retries() {
    }

    /**
     * Runs {@code attempt} until it succeeds, until it fails permanently, or
     * until the attempts run out.
     *
     * @return whatever the successful attempt produced
     * @throws TianggeCallFailed always, either permanent or after the last retry
     */
    static <T> T call(String what, Supplier<T> attempt) {
        long backoff = INITIAL_BACKOFF_MILLIS;
        TianggeCallFailed last = null;
        for (int tryNumber = 1; tryNumber <= MAX_ATTEMPTS; tryNumber++) {
            try {
                return attempt.get();
            } catch (TianggeCallFailed e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                last = e;
            }
            if (tryNumber == MAX_ATTEMPTS) {
                break;
            }
            ChannelLogger.get().warn("{} failed ({} {}), attempt {}/{} - retrying in {}ms",
                    what, last.status(), last.code(), tryNumber, MAX_ATTEMPTS, backoff);
            sleep(backoff);
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
        }
        ChannelLogger.get().warn("{} giving up after {} attempts: {}", what, MAX_ATTEMPTS, last.getMessage());
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TianggeCallFailed("Interrupted while backing off", 0, "interrupted", false);
        }
    }
}