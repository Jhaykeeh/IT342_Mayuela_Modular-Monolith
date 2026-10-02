package edu.cit.mayuela.channel;

/**
 * A Tiangge call that did not succeed.
 *
 * Carries everything the callers need to decide what to do next: the HTTP
 * status, Tiangge's own error code, and whether trying again could ever help.
 * A 4xx is always permanent - a bad sku or a wrong decision will still be wrong
 * in a second - so it is never retried, only reported.
 */
class TianggeCallFailed extends RuntimeException {

    private final int status;

    private final String code;

    private final boolean retryable;

    TianggeCallFailed(String message, int status, String code, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code == null ? "" : code;
        this.retryable = retryable;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }

    boolean isRetryable() {
        return retryable;
    }

    boolean isConflict() {
        return status == 409;
    }
}