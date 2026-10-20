package edu.eci.tdse.orders.lambda.proxy;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.eci.tdse.orders.domain.CreateOrderRequest;
import edu.eci.tdse.orders.domain.Order;
import edu.eci.tdse.orders.domain.OrderException;
import edu.eci.tdse.orders.domain.OrderNotFoundException;
import edu.eci.tdse.orders.domain.OrderService;
import edu.eci.tdse.orders.domain.OrdersApplication;
import edu.eci.tdse.orders.domain.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Arquitectura B (integración proxy): adaptador HTTP. Es la única clase que conoce rutas,
 * parámetros, headers y códigos de estado; delega la lógica a OrderService.
 */
public class OrdersApiHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    static final String ORDERS = "/orders";
    static final String ORDER_BY_ID = "/orders/{id}";

    static final String INVALID_REQUEST_BODY = "INVALID_REQUEST_BODY";
    static final String ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND";
    static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final OrderService DEFAULT_SERVICE = OrdersApplication.createService();

    private static final ObjectMapper JSON = new ObjectMapper()
            // Ignora campos extra (ej. un "id" enviado por el cliente).
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Evita que "quantity": 1.9 se convierta en 1.
            .configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false);

    private final OrderService service;

    /** Usado por el runtime de Lambda. */
    public OrdersApiHandler() {
        this(DEFAULT_SERVICE);
    }

    public OrdersApiHandler(OrderService service) {
        this.service = service;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        logEventIfEnabled(event, context);
        try {
            return route(event);
        } catch (OrderException e) {
            return error(statusFor(e), e.getCode(), e.getMessage());
        } catch (InvalidBodyException e) {
            return error(400, INVALID_REQUEST_BODY, e.getMessage());
        } catch (RuntimeException e) {
            // Error inesperado: detalle en CloudWatch, mensaje genérico al cliente.
            log(context, "Unexpected error: " + e);
            e.printStackTrace();
            return error(500, INTERNAL_ERROR, "Unexpected internal error");
        }
    }

    private APIGatewayProxyResponseEvent route(APIGatewayProxyRequestEvent event) {
        String resource = event.getResource();
        String method = Objects.toString(event.getHttpMethod(), "");

        if (ORDERS.equals(resource)) {
            switch (method) {
                case "POST":
                    return created(service.createOrder(parseBody(event, CreateOrderRequest.class)));
                case "GET":
                    return ok(service.getOrdersByCustomer(queryParam(event, "customerId")));
                default:
                    return methodNotAllowed(method, "GET, POST");
            }
        }
        if (ORDER_BY_ID.equals(resource)) {
            switch (method) {
                case "GET":
                    return ok(service.getOrder(pathParam(event, "id")));
                case "DELETE":
                    service.deleteOrder(pathParam(event, "id"));
                    return noContent();
                default:
                    return methodNotAllowed(method, "GET, DELETE");
            }
        }
        return error(404, ROUTE_NOT_FOUND, "No route for " + method + " " + event.getPath());
    }

    // ---- petición --------------------------------------------------------------------------

    private static String pathParam(APIGatewayProxyRequestEvent event, String name) {
        Map<String, String> params = event.getPathParameters();
        return params == null ? null : params.get(name);
    }

    private static String queryParam(APIGatewayProxyRequestEvent event, String name) {
        Map<String, String> params = event.getQueryStringParameters();
        return params == null ? null : params.get(name);
    }

    private static <T> T parseBody(APIGatewayProxyRequestEvent event, Class<T> type) {
        String body = event.getBody();
        if (body != null && Boolean.TRUE.equals(event.getIsBase64Encoded())) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        if (body == null || body.isBlank()) {
            throw new InvalidBodyException("Request body is required");
        }
        try {
            return JSON.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new InvalidBodyException("Request body must be a valid JSON object: "
                    + e.getOriginalMessage());
        }
    }

    // ---- respuesta -------------------------------------------------------------------------

    private static int statusFor(OrderException e) {
        if (e instanceof OrderNotFoundException) {
            return 404;
        }
        if (e instanceof ValidationException) {
            return 400;
        }
        return 500;
    }

    private static APIGatewayProxyResponseEvent ok(Object body) {
        return response(200, body, jsonHeaders());
    }

    private static APIGatewayProxyResponseEvent created(Order order) {
        Map<String, String> headers = jsonHeaders();
        headers.put("Location", ORDERS + "/" + order.getId());
        return response(201, order, headers);
    }

    /** 204 va sin body ni Content-Type. */
    private static APIGatewayProxyResponseEvent noContent() {
        return response(204, null, new HashMap<>());
    }

    private static APIGatewayProxyResponseEvent methodNotAllowed(String method, String allowed) {
        APIGatewayProxyResponseEvent response =
                error(405, METHOD_NOT_ALLOWED, "Method " + method + " is not allowed on this resource");
        response.getHeaders().put("Allow", allowed);
        return response;
    }

    private static APIGatewayProxyResponseEvent error(int status, String code, String message) {
        return response(status, new ErrorResponse(code, message), jsonHeaders());
    }

    private static APIGatewayProxyResponseEvent response(int status, Object body, Map<String, String> headers) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(headers);
        if (body != null) {
            response.setBody(toJson(body));
        }
        return response;
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize response", e);
        }
    }

    // ---- diagnóstico -----------------------------------------------------------------------

    /** Con LOG_EVENTS=true imprime el evento completo en CloudWatch. */
    private static void logEventIfEnabled(APIGatewayProxyRequestEvent event, Context context) {
        if ("true".equalsIgnoreCase(System.getenv("LOG_EVENTS"))) {
            try {
                log(context, "Proxy event: " + JSON.writeValueAsString(event));
            } catch (JsonProcessingException e) {
                log(context, "Proxy event (toString): " + event);
            }
        }
    }

    private static void log(Context context, String message) {
        if (context != null) {
            context.getLogger().log(message + "\n");
        } else {
            System.err.println(message);
        }
    }

    /** Body HTTP mal formado (problema HTTP, por eso vive en el adaptador). */
    private static final class InvalidBodyException extends RuntimeException {
        InvalidBodyException(String message) {
            super(message);
        }
    }
}
