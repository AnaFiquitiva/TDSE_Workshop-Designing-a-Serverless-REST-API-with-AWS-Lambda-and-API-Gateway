package edu.eci.tdse.orders.domain;

/** El pedido solicitado no existe. */
public class OrderNotFoundException extends OrderException {

    public static final String CODE = "ORDER_NOT_FOUND";

    public OrderNotFoundException(String orderId) {
        super(CODE, "Order " + orderId + " does not exist");
    }
}
