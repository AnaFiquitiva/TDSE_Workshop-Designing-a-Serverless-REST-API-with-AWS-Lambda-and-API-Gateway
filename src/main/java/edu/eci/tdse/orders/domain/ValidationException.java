package edu.eci.tdse.orders.domain;

/** The input violates a business rule (missing customer, non-positive quantity, ...). */
public class ValidationException extends OrderException {

    public static final String CODE = "VALIDATION_ERROR";

    public ValidationException(String message) {
        super(CODE, message);
    }
}
