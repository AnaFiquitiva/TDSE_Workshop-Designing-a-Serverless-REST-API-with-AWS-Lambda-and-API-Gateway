package edu.eci.tdse.orders.domain;

/** Construye el OrderService con pedidos de ejemplo precargados. */
public final class OrdersApplication {

    private OrdersApplication() {
    }

    public static OrderService createService() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        repository.save(new Order("ORD-001", "CUST-42", "MacBook Air", 1));
        repository.save(new Order("ORD-002", "CUST-42", "USB-C Hub", 2));
        repository.save(new Order("ORD-003", "CUST-100", "Monitor 27\"", 1));
        repository.save(new Order("ORD-42", "CUST-7", "Keyboard", 2));
        return new OrderService(repository);
    }
}
