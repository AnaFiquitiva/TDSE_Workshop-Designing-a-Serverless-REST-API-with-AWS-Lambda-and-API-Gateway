package edu.eci.tdse.orders.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Pedidos en un mapa en memoria. No es persistente: se pierde en cada cold start de Lambda. */
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @Override
    public Order save(Order order) {
        orders.put(order.getId(), order);
        return order;
    }

    @Override
    public Optional<Order> findById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    @Override
    public List<Order> findByCustomerId(String customerId) {
        return orders.values().stream()
                .filter(order -> order.getCustomerId().equals(customerId))
                .sorted(Comparator.comparing(Order::getId))
                .toList();
    }

    @Override
    public boolean deleteById(String id) {
        return orders.remove(id) != null;
    }
}
