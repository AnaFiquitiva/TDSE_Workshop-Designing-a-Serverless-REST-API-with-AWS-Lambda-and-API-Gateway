package edu.eci.tdse.orders.domain;

/**
 * Base class for expected business failures.
 *
 * <p>Each failure carries a stable, machine-readable {@code code} (e.g. {@code ORDER_NOT_FOUND}).
 * The code describes <em>what</em> went wrong in business terms; translating it into an HTTP
 * status code is the job of the adapters, not of the domain.
 */
public abstract class OrderException extends RuntimeException {

    private final String code;

    protected OrderException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
