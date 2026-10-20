package edu.eci.tdse.orders.domain;

/** La entrada viola una regla de negocio (cliente vacío, cantidad menor a 1, ...). */
public class ValidationException extends OrderException {

    public static final String CODE = "VALIDATION_ERROR";

    public ValidationException(String message) {
        super(CODE, message);
    }
}
