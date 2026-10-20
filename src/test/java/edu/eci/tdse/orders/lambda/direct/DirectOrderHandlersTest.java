package edu.eci.tdse.orders.lambda.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.eci.tdse.orders.domain.CreateOrderRequest;
import edu.eci.tdse.orders.domain.InMemoryOrderRepository;
import edu.eci.tdse.orders.domain.Order;
import edu.eci.tdse.orders.domain.OrderService;
import edu.eci.tdse.orders.domain.OrdersApplication;
import org.junit.jupiter.api.Test;

/** Handlers de la arquitectura A. Los mensajes de error deben coincidir con las regex de API Gateway. */
class DirectOrderHandlersTest {

    private final DirectOrderHandlers handlers = new DirectOrderHandlers(OrdersApplication.createService());

    @Test
    void createsOrder() {
        Order order = handlers.createOrder(new CreateOrderRequest("CUST-42", "MacBook Air", 1));

        assertTrue(order.getId().startsWith("ORD-"));
        assertEquals("CUST-42", order.getCustomerId());
    }

    @Test
    void getsSeededOrder() {
        assertEquals(new Order("ORD-42", "CUST-7", "Keyboard", 2), handlers.getOrder(new OrderIdInput("ORD-42")));
    }

    @Test
    void findsOrdersByCustomer() {
        assertEquals(2, handlers.getOrdersByCustomer(new CustomerQueryInput("CUST-42")).size());
        assertEquals(1, handlers.getOrdersByCustomer(new CustomerQueryInput("CUST-100")).size());
    }

    @Test
    void missingOrderMessageStartsWithCode() {
        DirectInvocationException e = assertThrows(DirectInvocationException.class,
                () -> handlers.getOrder(new OrderIdInput("ORD-999")));

        assertEquals("ORDER_NOT_FOUND: Order ORD-999 does not exist", e.getMessage());
    }

    @Test
    void invalidInputMessageStartsWithCode() {
        DirectInvocationException e = assertThrows(DirectInvocationException.class,
                () -> handlers.createOrder(new CreateOrderRequest("CUST-42", "MacBook Air", 0)));

        assertEquals("VALIDATION_ERROR: quantity must be greater than 0", e.getMessage());
    }

    @Test
    void nullInputIsValidationError() {
        DirectInvocationException e = assertThrows(DirectInvocationException.class,
                () -> handlers.getOrdersByCustomer(null));

        assertTrue(e.getMessage().startsWith("VALIDATION_ERROR: "), e.getMessage());
    }

    @Test
    void unexpectedFailureIsHiddenBehindInternalError() {
        OrderService broken = new OrderService(new InMemoryOrderRepository(), () -> {
            throw new IllegalStateException("id generator exploded");
        });
        DirectOrderHandlers brokenHandlers = new DirectOrderHandlers(broken);

        DirectInvocationException e = assertThrows(DirectInvocationException.class,
                () -> brokenHandlers.createOrder(new CreateOrderRequest("CUST-1", "A", 1)));

        assertEquals("INTERNAL_ERROR: Unexpected internal error", e.getMessage());
    }
}
