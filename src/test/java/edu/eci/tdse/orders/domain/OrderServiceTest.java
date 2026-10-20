package edu.eci.tdse.orders.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plain-Java tests: no AWS, no HTTP, no web server. */
class OrderServiceTest {

    private OrderService service;

    @BeforeEach
    void setUp() {
        AtomicInteger sequence = new AtomicInteger();
        service = new OrderService(new InMemoryOrderRepository(),
                () -> "ORD-T" + sequence.incrementAndGet());
    }

    @Test
    void createsValidOrder() {
        Order order = service.createOrder(new CreateOrderRequest("CUST-42", "MacBook Air", 1));

        assertEquals(new Order("ORD-T1", "CUST-42", "MacBook Air", 1), order);
    }

    @Test
    void trimsTextFields() {
        Order order = service.createOrder(new CreateOrderRequest("  CUST-42 ", " MacBook Air ", 1));

        assertEquals("CUST-42", order.getCustomerId());
        assertEquals("MacBook Air", order.getProduct());
    }

    @Test
    void retrievesExistingOrder() {
        Order created = service.createOrder(new CreateOrderRequest("CUST-7", "Keyboard", 2));

        assertEquals(created, service.getOrder(created.getId()));
    }

    @Test
    void failsWhenOrderDoesNotExist() {
        OrderNotFoundException e = assertThrows(OrderNotFoundException.class,
                () -> service.getOrder("ORD-999"));

        assertEquals("ORDER_NOT_FOUND", e.getCode());
        assertEquals("Order ORD-999 does not exist", e.getMessage());
    }

    @Test
    void findsOrdersOfOneCustomerOnly() {
        Order first = service.createOrder(new CreateOrderRequest("CUST-42", "MacBook Air", 1));
        Order second = service.createOrder(new CreateOrderRequest("CUST-42", "Mouse", 3));
        service.createOrder(new CreateOrderRequest("CUST-100", "Monitor", 1));

        assertEquals(List.of(first, second), service.getOrdersByCustomer("CUST-42"));
    }

    @Test
    void returnsEmptyListForCustomerWithoutOrders() {
        assertTrue(service.getOrdersByCustomer("CUST-NOBODY").isEmpty());
    }

    @Test
    void rejectsMissingCustomer() {
        assertValidationError("customerId is required",
                new CreateOrderRequest(null, "MacBook Air", 1));
        assertValidationError("customerId is required",
                new CreateOrderRequest("   ", "MacBook Air", 1));
    }

    @Test
    void rejectsMissingProduct() {
        assertValidationError("product is required", new CreateOrderRequest("CUST-42", "", 1));
    }

    @Test
    void rejectsMissingOrNonPositiveQuantity() {
        assertValidationError("quantity is required", new CreateOrderRequest("CUST-42", "Mouse", null));
        assertValidationError("quantity must be greater than 0", new CreateOrderRequest("CUST-42", "Mouse", 0));
        assertValidationError("quantity must be greater than 0", new CreateOrderRequest("CUST-42", "Mouse", -5));
    }

    @Test
    void rejectsNullRequest() {
        assertThrows(ValidationException.class, () -> service.createOrder(null));
    }

    @Test
    void rejectsBlankIdAndCustomerInQueries() {
        assertThrows(ValidationException.class, () -> service.getOrder(" "));
        assertThrows(ValidationException.class, () -> service.getOrdersByCustomer(null));
    }

    @Test
    void defaultIdGeneratorProducesDistinctIds() {
        OrderService realIds = new OrderService(new InMemoryOrderRepository());
        Order a = realIds.createOrder(new CreateOrderRequest("CUST-1", "A", 1));
        Order b = realIds.createOrder(new CreateOrderRequest("CUST-1", "B", 1));

        assertTrue(a.getId().matches("ORD-[0-9A-F]{8}"), a.getId());
        assertNotEquals(a.getId(), b.getId());
    }

    @Test
    void seededApplicationContainsPredefinedOrders() {
        OrderService seeded = OrdersApplication.createService();

        assertEquals("Keyboard", seeded.getOrder("ORD-42").getProduct());
        assertEquals(2, seeded.getOrdersByCustomer("CUST-42").size());
    }

    private void assertValidationError(String expectedMessage, CreateOrderRequest request) {
        ValidationException e = assertThrows(ValidationException.class, () -> service.createOrder(request));
        assertEquals("VALIDATION_ERROR", e.getCode());
        assertEquals(expectedMessage, e.getMessage());
    }
}
