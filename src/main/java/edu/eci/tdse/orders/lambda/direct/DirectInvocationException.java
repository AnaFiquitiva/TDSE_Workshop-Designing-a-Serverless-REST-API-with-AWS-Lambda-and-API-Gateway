package edu.eci.tdse.orders.lambda.direct;

/**
 * Thrown out of a direct (non-proxy) handler so that API Gateway can map it to an HTTP status.
 *
 * <p>The message format {@code "<CODE>: <human message>"} is a contract with the integration
 * response selection patterns in {@code infrastructure/openapi-mapping.yaml}.
 */
public class DirectInvocationException extends RuntimeException {

    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public DirectInvocationException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
    }
}
