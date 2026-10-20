package edu.eci.tdse.orders.lambda.direct;

/** JSON built by the GET /orders?customerId=... mapping template: {@code {"customerId": "CUST-42"}}. */
public class CustomerQueryInput {

    private String customerId;

    public CustomerQueryInput() {
    }

    public CustomerQueryInput(String customerId) {
        this.customerId = customerId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
}
