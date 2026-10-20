package edu.eci.tdse.orders.domain;

/** The requested order does not exist. */
public class OrderNotFoundException extends OrderException {

    public static final String CODE = "ORDER_NOT_FOUND";

    public OrderNotFoundException(String orderId) {
        super(CODE, "Order " + orderId + " does not exist");
    }
}
