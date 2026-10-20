package edu.eci.tdse.orders.lambda.proxy;

/** Cuerpo de error: {"error": "ORDER_NOT_FOUND", "message": "..."}. */
public class ErrorResponse {

    private final String error;
    private final String message;

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.message = message;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }
}
