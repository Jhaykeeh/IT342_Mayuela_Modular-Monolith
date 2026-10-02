package edu.cit.mayuela.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The identity of this running process, shared by every outbound adapter.
 *
 * A new UUID is generated once per JVM start, in the static initialiser, so a
 * restart always produces a different instance id. Both partner systems identify
 * a caller by this id: LegacySupply receives it as {@code X-Client-Instance} on
 * every request, Tiangge receives it on every call as well.
 *
 * Deliberately dependency-free and tiny: it is the one shared piece of
 * infrastructure the supplier adapter and the Tiangge channel have in common.
 */
public final class AppInstance {

    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    private static final Instant STARTED_AT = Instant.now();

    private AppInstance() {
    }

    /** New per-JVM identifier sent as {@code X-Client-Instance}. */
    public static String instanceId() {
        return INSTANCE_ID;
    }

    /** Moment this JVM started; reported in the Tiangge heartbeat. */
    public static Instant startedAt() {
        return STARTED_AT;
    }

    /** Seconds since startup, as reported in the Tiangge heartbeat. */
    public static long uptimeSeconds() {
        return Duration.between(STARTED_AT, Instant.now()).toSeconds();
    }
}