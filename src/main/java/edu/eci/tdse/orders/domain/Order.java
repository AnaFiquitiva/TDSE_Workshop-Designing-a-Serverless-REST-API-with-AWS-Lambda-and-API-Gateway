package edu.eci.tdse.orders.domain;

import java.util.Objects;

/** Pedido de un cliente. Inmutable; usa getters (no record) porque Lambda serializa con getters. */
public final class Order {

    private final String id;
    private final String customerId;
    private final String product;
    private final int quantity;

    public Order(String id, String customerId, String product, int quantity) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.product = Objects.requireNonNull(product, "product");
        this.quantity = quantity;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Order other)) {
            return false;
        }
        return quantity == other.quantity
                && id.equals(other.id)
                && customerId.equals(other.customerId)
                && product.equals(other.product);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, customerId, product, quantity);
    }

    @Override
    public String toString() {
        return "Order{id='" + id + "', customerId='" + customerId + "', product='" + product
                + "', quantity=" + quantity + '}';
    }
}
