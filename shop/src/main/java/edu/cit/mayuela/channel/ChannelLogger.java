package edu.cit.mayuela.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One shared logger name for the whole channel, so every line of marketplace
 * traffic can be grepped in one place when reviewing evidence.
 */
final class ChannelLogger {

    private ChannelLogger() {
    }

    static Logger get() {
        return LoggerFactory.getLogger("channel.tiangge");
    }
}