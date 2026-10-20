package edu.eci.tdse.orders.lambda.direct;

/** Entrada de GET /orders?customerId=...: {"customerId": "CUST-42"}. */
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
