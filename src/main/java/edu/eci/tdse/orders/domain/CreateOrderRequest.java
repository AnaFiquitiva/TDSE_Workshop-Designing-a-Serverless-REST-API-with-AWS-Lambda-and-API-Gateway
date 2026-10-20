package edu.eci.tdse.orders.domain;

/**
 * Data needed to create an order. The id is never supplied by the caller; the service assigns it.
 *
 * <p>Mutable JavaBean with a no-arg constructor and setters so that both the Lambda runtime
 * serializer (Architecture A) and Jackson (Architecture B) can build it from JSON. {@code quantity}
 * is an {@link Integer} so that a missing value ({@code null}) can be told apart from {@code 0}.
 */
public class CreateOrderRequest {

    private String customerId;
    private String product;
    private Integer quantity;

    public CreateOrderRequest() {
    }

    public CreateOrderRequest(String customerId, String product, Integer quantity) {
        this.customerId = customerId;
        this.product = product;
        this.quantity = quantity;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
