package edu.eci.tdse.orders.domain;

import java.util.List;
import java.util.Optional;

/** Storage port. The workshop uses an in-memory implementation; a DynamoDB one could replace it. */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(String id);

    List<Order> findByCustomerId(String customerId);

    /** Removes the order; returns {@code false} if there was no order with that id. */
    boolean deleteById(String id);
}
