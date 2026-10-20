package edu.eci.tdse.orders.lambda.direct;

import edu.eci.tdse.orders.domain.CreateOrderRequest;
import edu.eci.tdse.orders.domain.Order;
import edu.eci.tdse.orders.domain.OrderException;
import edu.eci.tdse.orders.domain.OrderService;
import edu.eci.tdse.orders.domain.OrdersApplication;
import java.util.List;
import java.util.function.Supplier;

/**
 * Arquitectura A (integración sin proxy): cada método es un handler de Lambda.
 * Los errores se lanzan como "CODIGO: mensaje" para que API Gateway los mapee con regex.
 */
public class DirectOrderHandlers {

    // Estático para que se conserve entre invocaciones de la misma instancia.
    private static final OrderService DEFAULT_SERVICE = OrdersApplication.createService();

    private final OrderService service;

    /** Usado por el runtime de Lambda. */
    public DirectOrderHandlers() {
        this(DEFAULT_SERVICE);
    }

    public DirectOrderHandlers(OrderService service) {
        this.service = service;
    }

    /** Entrada: {"customerId": "...", "product": "...", "quantity": 1}. */
    public Order createOrder(CreateOrderRequest request) {
        return invoke(() -> service.createOrder(request));
    }

    /** Entrada: {"id": "ORD-42"}. */
    public Order getOrder(OrderIdInput input) {
        return invoke(() -> service.getOrder(input == null ? null : input.getId()));
    }

    /** Entrada: {"customerId": "CUST-42"}. */
    public List<Order> getOrdersByCustomer(CustomerQueryInput input) {
        return invoke(() -> service.getOrdersByCustomer(input == null ? null : input.getCustomerId()));
    }

    private static <T> T invoke(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (OrderException e) {
            throw new DirectInvocationException(e.getCode(), e.getMessage(), e);
        } catch (RuntimeException e) {
            // Se registra el detalle en el log y se oculta al cliente.
            e.printStackTrace();
            throw new DirectInvocationException(
                    DirectInvocationException.INTERNAL_ERROR, "Unexpected internal error", e);
        }
    }
}
