package edu.eci.tdse.orders.domain;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Application logic of the Order Management API.
 *
 * <p>Plain Java: no HTTP, no API Gateway, no Lambda types. Failures are reported with
 * {@link OrderNotFoundException} and {@link ValidationException}; it is up to each adapter to
 * decide what those mean in its own protocol.
 */
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

    /** Returns the customer's orders, or an empty list if the customer has none. */
    public List<Order> getOrdersByCustomer(String customerId) {
        return repository.findByCustomerId(requireText(customerId, "customerId"));
    }

    /** Deletes the order; fails with {@link OrderNotFoundException} if it does not exist. */
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

    /**
     * Random ids instead of a counter: each Lambda execution environment has its own memory, so a
     * counter starting at 1 in every environment would hand out duplicate ids.
     */
    private static String randomId() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
