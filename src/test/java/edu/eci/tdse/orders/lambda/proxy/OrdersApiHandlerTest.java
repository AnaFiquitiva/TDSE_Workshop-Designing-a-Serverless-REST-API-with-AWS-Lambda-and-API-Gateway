package edu.eci.tdse.orders.lambda.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.eci.tdse.orders.domain.InMemoryOrderRepository;
import edu.eci.tdse.orders.domain.OrderService;
import edu.eci.tdse.orders.domain.OrdersApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Arquitectura B: se prueba el adaptador HTTP localmente con eventos proxy armados a mano. */
class OrdersApiHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OrdersApiHandler handler = new OrdersApiHandler(OrdersApplication.createService());

    // ---- casos exitosos --------------------------------------------------------------------

    @Test
    void getExistingOrderReturns200() throws IOException {
        APIGatewayProxyResponseEvent response = handle(get("/orders/{id}", "/orders/ORD-42")
                .withPathParameters(Map.of("id", "ORD-42")));

        assertEquals(200, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        JsonNode body = body(response);
        assertEquals("ORD-42", body.get("id").asText());
        assertEquals("CUST-7", body.get("customerId").asText());
        assertEquals("Keyboard", body.get("product").asText());
        assertEquals(2, body.get("quantity").asInt());
    }

    @Test
    void postReturns201WithLocationAndNewOrderIsRetrievable() throws IOException {
        APIGatewayProxyResponseEvent created = handle(post(
                "{\"customerId\":\"CUST-42\",\"product\":\"MacBook Air\",\"quantity\":1}"));

        assertEquals(201, created.getStatusCode());
        String id = body(created).get("id").asText();
        assertEquals("/orders/" + id, created.getHeaders().get("Location"));

        APIGatewayProxyResponseEvent fetched = handle(get("/orders/{id}", "/orders/" + id)
                .withPathParameters(Map.of("id", id)));
        assertEquals(200, fetched.getStatusCode());
        assertEquals("MacBook Air", body(fetched).get("product").asText());
    }

    @Test
    void searchByCustomerReturns200WithArray() throws IOException {
        APIGatewayProxyResponseEvent response = handle(get("/orders", "/orders")
                .withQueryStringParameters(Map.of("customerId", "CUST-42")));

        assertEquals(200, response.getStatusCode());
        JsonNode body = body(response);
        assertTrue(body.isArray());
        assertEquals(2, body.size());
    }

    @Test
    void searchForCustomerWithoutOrdersReturnsEmptyArray() throws IOException {
        APIGatewayProxyResponseEvent response = handle(get("/orders", "/orders")
                .withQueryStringParameters(Map.of("customerId", "CUST-NOBODY")));

        assertEquals(200, response.getStatusCode());
        assertEquals("[]", response.getBody());
    }

    @Test
    void deleteExistingOrderReturns204WithoutBodyAndOrderIsGone() throws IOException {
        APIGatewayProxyResponseEvent deleted = handle(delete("ORD-42"));

        assertEquals(204, deleted.getStatusCode());
        assertEquals(null, deleted.getBody());
        assertEquals(null, deleted.getHeaders().get("Content-Type"));

        APIGatewayProxyResponseEvent fetched = handle(get("/orders/{id}", "/orders/ORD-42")
                .withPathParameters(Map.of("id", "ORD-42")));
        assertError(fetched, 404, "ORDER_NOT_FOUND", "Order ORD-42 does not exist");
    }

    @Test
    void deleteMissingOrderReturns404() throws IOException {
        assertError(handle(delete("ORD-999")), 404, "ORDER_NOT_FOUND", "Order ORD-999 does not exist");
    }

    @Test
    void deletingTwiceReturns204Then404() {
        assertEquals(204, handle(delete("ORD-001")).getStatusCode());
        assertEquals(404, handle(delete("ORD-001")).getStatusCode());
    }

    @Test
    void acceptsBase64EncodedBody() {
        String json = "{\"customerId\":\"CUST-1\",\"product\":\"Pen\",\"quantity\":5}";
        APIGatewayProxyRequestEvent event = post(Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8))).withIsBase64Encoded(true);

        assertEquals(201, handle(event).getStatusCode());
    }

    // ---- casos de error --------------------------------------------------------------------

    @Test
    void missingOrderReturns404() throws IOException {
        APIGatewayProxyResponseEvent response = handle(get("/orders/{id}", "/orders/ORD-999")
                .withPathParameters(Map.of("id", "ORD-999")));

        assertError(response, 404, "ORDER_NOT_FOUND", "Order ORD-999 does not exist");
    }

    @Test
    void invalidOrderReturns400() throws IOException {
        APIGatewayProxyResponseEvent response = handle(post(
                "{\"customerId\":\"CUST-42\",\"product\":\"MacBook Air\",\"quantity\":0}"));

        assertError(response, 400, "VALIDATION_ERROR", "quantity must be greater than 0");
    }

    @Test
    void missingFieldReturns400() throws IOException {
        APIGatewayProxyResponseEvent response = handle(post("{\"product\":\"MacBook Air\",\"quantity\":1}"));

        assertError(response, 400, "VALIDATION_ERROR", "customerId is required");
    }

    @Test
    void malformedJsonReturns400() throws IOException {
        APIGatewayProxyResponseEvent response = handle(post("{\"customerId\": "));

        assertEquals(400, response.getStatusCode());
        assertEquals("INVALID_REQUEST_BODY", body(response).get("error").asText());
    }

    @Test
    void wrongTypeReturns400() throws IOException {
        APIGatewayProxyResponseEvent notANumber = handle(post(
                "{\"customerId\":\"CUST-42\",\"product\":\"Mouse\",\"quantity\":\"many\"}"));
        APIGatewayProxyResponseEvent fractional = handle(post(
                "{\"customerId\":\"CUST-42\",\"product\":\"Mouse\",\"quantity\":1.5}"));

        assertEquals("INVALID_REQUEST_BODY", body(notANumber).get("error").asText());
        assertEquals(400, fractional.getStatusCode());
    }

    @Test
    void emptyBodyReturns400() throws IOException {
        assertError(handle(post(null)), 400, "INVALID_REQUEST_BODY", "Request body is required");
    }

    @Test
    void searchWithoutCustomerIdReturns400() throws IOException {
        assertError(handle(get("/orders", "/orders")), 400, "VALIDATION_ERROR", "customerId is required");
    }

    @Test
    void unsupportedMethodReturns405WithAllowHeader() throws IOException {
        APIGatewayProxyResponseEvent response = handle(new APIGatewayProxyRequestEvent()
                .withResource("/orders").withPath("/orders").withHttpMethod("PUT"));

        assertEquals(405, response.getStatusCode());
        assertEquals("GET, POST", response.getHeaders().get("Allow"));
        assertEquals("METHOD_NOT_ALLOWED", body(response).get("error").asText());
    }

    @Test
    void allowHeaderOnSingleOrderListsDelete() {
        APIGatewayProxyResponseEvent response = handle(new APIGatewayProxyRequestEvent()
                .withResource("/orders/{id}").withPath("/orders/ORD-42").withHttpMethod("PATCH")
                .withPathParameters(Map.of("id", "ORD-42")));

        assertEquals(405, response.getStatusCode());
        assertEquals("GET, DELETE", response.getHeaders().get("Allow"));
    }

    @Test
    void unknownRouteReturns404() throws IOException {
        APIGatewayProxyResponseEvent response = handle(get("/customers", "/customers"));

        assertError(response, 404, "ROUTE_NOT_FOUND", "No route for GET /customers");
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() throws IOException {
        OrdersApiHandler broken = new OrdersApiHandler(new OrderService(new InMemoryOrderRepository(), () -> {
            throw new IllegalStateException("internal detail that must not reach the client");
        }));

        APIGatewayProxyResponseEvent response = broken.handleRequest(
                post("{\"customerId\":\"CUST-1\",\"product\":\"A\",\"quantity\":1}"), null);

        assertError(response, 500, "INTERNAL_ERROR", "Unexpected internal error");
        assertTrue(!response.getBody().contains("internal detail"));
    }

    // ---- eventos guardados en events/ ------------------------------------------------------

    @Test
    void handlesSavedProxyEvents() throws IOException {
        assertEquals(200, handle(loadEvent("proxy-get-order.json")).getStatusCode());
        assertEquals(200, handle(loadEvent("proxy-find-orders.json")).getStatusCode());
        assertEquals(201, handle(loadEvent("proxy-create-order.json")).getStatusCode());
    }

    // ---- utilidades ------------------------------------------------------------------------

    private APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event) {
        return handler.handleRequest(event, null);
    }

    private static APIGatewayProxyRequestEvent get(String resource, String path) {
        return new APIGatewayProxyRequestEvent().withHttpMethod("GET").withResource(resource).withPath(path);
    }

    private static APIGatewayProxyRequestEvent delete(String id) {
        return new APIGatewayProxyRequestEvent().withHttpMethod("DELETE").withResource("/orders/{id}")
                .withPath("/orders/" + id).withPathParameters(Map.of("id", id));
    }

    private static APIGatewayProxyRequestEvent post(String body) {
        return new APIGatewayProxyRequestEvent().withHttpMethod("POST").withResource("/orders")
                .withPath("/orders").withHeaders(Map.of("Content-Type", "application/json")).withBody(body);
    }

    private static JsonNode body(APIGatewayProxyResponseEvent response) throws IOException {
        return JSON.readTree(response.getBody());
    }

    private static void assertError(APIGatewayProxyResponseEvent response, int status, String code, String message)
            throws IOException {
        assertEquals(status, response.getStatusCode());
        JsonNode body = body(response);
        assertEquals(code, body.get("error").asText());
        assertEquals(message, body.get("message").asText());
    }

    private static APIGatewayProxyRequestEvent loadEvent(String file) throws IOException {
        return JSON.readValue(Files.readString(Path.of("events", file)), APIGatewayProxyRequestEvent.class);
    }
}
