package edu.eci.tdse.orders.domain;

/** Error de negocio con un código (ej. ORDER_NOT_FOUND). Los adaptadores lo traducen a HTTP. */
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
