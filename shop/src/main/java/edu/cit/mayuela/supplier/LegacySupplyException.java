package edu.cit.mayuela.supplier;

/** Raised by the LegacySupply adapter; never leaves the supplier module. */
class LegacySupplyException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    LegacySupplyException(String code, String message, boolean retryable) {
        super(code + " " + message);
        this.code = code;
        this.retryable = retryable;
    }

    LegacySupplyException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = "NETWORK";
        this.retryable = retryable;
    }

    String getCode() {
        return code;
    }

    boolean isRetryable() {
        return retryable;
    }
}