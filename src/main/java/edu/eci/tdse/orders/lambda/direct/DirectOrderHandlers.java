package edu.eci.tdse.orders.lambda.direct;

import edu.eci.tdse.orders.domain.CreateOrderRequest;
import edu.eci.tdse.orders.domain.Order;
import edu.eci.tdse.orders.domain.OrderException;
import edu.eci.tdse.orders.domain.OrderService;
import edu.eci.tdse.orders.domain.OrdersApplication;
import java.util.List;
import java.util.function.Supplier;

/**
 * Architecture A: Lambda entry points for API Gateway <em>non-proxy</em> (custom) integration.
 *
 * <p>Every public method is a separate Lambda handler, configured as
 * {@code edu.eci.tdse.orders.lambda.direct.DirectOrderHandlers::<method>}. The Lambda runtime
 * deserializes the JSON produced by the API Gateway mapping template into the parameter type and
 * serializes the return value back to JSON, so this class needs no AWS library at all.
 *
 * <p>Errors are signalled by throwing. API Gateway sees the exception message as
 * {@code errorMessage} and picks an HTTP status with a regex on it, so every message starts with
 * a code: {@code "ORDER_NOT_FOUND: Order ORD-999 does not exist"}.
 */
public class DirectOrderHandlers {

    // Static so that it survives across invocations in the same (warm) execution environment.
    private static final OrderService DEFAULT_SERVICE = OrdersApplication.createService();

    private final OrderService service;

    /** Used by the Lambda runtime. */
    public DirectOrderHandlers() {
        this(DEFAULT_SERVICE);
    }

    public DirectOrderHandlers(OrderService service) {
        this.service = service;
    }

    /** Input: {@code {"customerId": "...", "product": "...", "quantity": 1}}. */
    public Order createOrder(CreateOrderRequest request) {
        return invoke(() -> service.createOrder(request));
    }

    /** Input: {@code {"id": "ORD-42"}}. */
    public Order getOrder(OrderIdInput input) {
        return invoke(() -> service.getOrder(input == null ? null : input.getId()));
    }

    /** Input: {@code {"customerId": "CUST-42"}}. */
    public List<Order> getOrdersByCustomer(CustomerQueryInput input) {
        return invoke(() -> service.getOrdersByCustomer(input == null ? null : input.getCustomerId()));
    }

    private static <T> T invoke(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (OrderException e) {
            throw new DirectInvocationException(e.getCode(), e.getMessage(), e);
        } catch (RuntimeException e) {
            // Log the details for us, hide them from the client.
            e.printStackTrace();
            throw new DirectInvocationException(
                    DirectInvocationException.INTERNAL_ERROR, "Unexpected internal error", e);
        }
    }
}
