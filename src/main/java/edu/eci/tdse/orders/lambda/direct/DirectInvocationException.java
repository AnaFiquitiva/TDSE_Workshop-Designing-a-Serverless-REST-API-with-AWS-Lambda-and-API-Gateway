package edu.eci.tdse.orders.lambda.direct;

/** Error con mensaje "CODIGO: texto"; debe coincidir con las regex de template.yaml. */
public class DirectInvocationException extends RuntimeException {

    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public DirectInvocationException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
    }
}
