package edu.eci.tdse.orders.lambda.direct;

/** JSON built by the GET /orders/{id} mapping template: {@code {"id": "ORD-42"}}. */
public class OrderIdInput {

    private String id;

    public OrderIdInput() {
    }

    public OrderIdInput(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
