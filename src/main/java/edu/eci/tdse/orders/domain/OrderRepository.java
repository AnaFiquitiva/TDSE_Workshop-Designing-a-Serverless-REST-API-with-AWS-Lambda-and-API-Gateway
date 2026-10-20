package edu.eci.tdse.orders.domain;

import java.util.List;
import java.util.Optional;

/** Almacenamiento de pedidos (en memoria en el taller; podría ser DynamoDB). */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(String id);

    List<Order> findByCustomerId(String customerId);

    /** Elimina el pedido; retorna false si no existía. */
    boolean deleteById(String id);
}
