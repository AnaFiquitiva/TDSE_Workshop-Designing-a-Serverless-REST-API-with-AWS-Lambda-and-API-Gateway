package edu.eci.tdse.orders.domain;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/** Lógica de negocio de pedidos. Java puro: sin HTTP, API Gateway ni Lambda. */
public class OrderService {

    private final OrderRepository repository;
    private final Supplier<String> idGenerator;

    public OrderService(OrderRepository repository) {
        this(repository, OrderService::randomId);
    }

    public OrderService(OrderRepository repository, Supplier<String> idGenerator) {
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    public Order createOrder(CreateOrderRequest request) {
        if (request == null) {
            throw new ValidationException("Order data is required");
        }
        String customerId = requireText(request.getCustomerId(), "customerId");
        String product = requireText(request.getProduct(), "product");
        Integer quantity = request.getQuantity();
        if (quantity == null) {
            throw new ValidationException("quantity is required");
        }
        if (quantity < 1) {
            throw new ValidationException("quantity must be greater than 0");
        }
        return repository.save(new Order(idGenerator.get(), customerId, product, quantity));
    }

    public Order getOrder(String id) {
        String orderId = requireText(id, "id");
        return repository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /** Pedidos del cliente; lista vacía si no tiene. */
    public List<Order> getOrdersByCustomer(String customerId) {
        return repository.findByCustomerId(requireText(customerId, "customerId"));
    }

    /** Elimina el pedido; lanza OrderNotFoundException si no existe. */
    public void deleteOrder(String id) {
        String orderId = requireText(id, "id");
        if (!repository.deleteById(orderId)) {
            throw new OrderNotFoundException(orderId);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " is required");
        }
        return value.trim();
    }

    /** Id aleatorio: un contador se repetiría entre instancias de Lambda. */
    private static String randomId() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
