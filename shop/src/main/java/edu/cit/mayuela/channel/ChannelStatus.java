package edu.cit.mayuela.channel;

/**
 * The one public door of the Tiangge channel.
 *
 * Everything else in this package is package-private, so this interface is the
 * only way anything outside the channel can ask how the marketplace connection
 * is doing. It is read-only on purpose: a caller may observe the channel, never
 * steer it.
 *
 * @see ChannelStatusImpl
 */
public interface ChannelStatus {

    /** This JVM's instance id, the same value sent as X-Client-Instance. */
    String instanceId();

    /** The feed position already processed; where a restart resumes from. */
    long feedCursor();

    /**
     * True once the heartbeat, the listings and the first stock publish have all
     * succeeded. The poller and the heartbeat only run while this is true, so
     * a marketplace that is down at boot cannot produce a stream of errors.
     */
    boolean online();
}