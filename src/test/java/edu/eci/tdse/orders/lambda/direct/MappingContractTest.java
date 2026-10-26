package edu.eci.tdse.orders.lambda.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.eci.tdse.orders.domain.CreateOrderRequest;
import edu.eci.tdse.orders.domain.InMemoryOrderRepository;
import edu.eci.tdse.orders.domain.OrderService;
import edu.eci.tdse.orders.domain.OrdersApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Contrato de strings de la arquitectura A: los mensajes "CODIGO: texto" que lanza Java deben
 * coincidir con las regex (selection patterns) de las integration responses en template.yaml,
 * y el template VTL debe recuperar el mensaje del dominio. Se lee el YAML real, sin desplegar.
 */
class MappingContractTest {

    private static final Pattern URI = Pattern.compile("\\$\\{(\\w+)Function\\.Arn}");
    private static final Pattern SELECTION = Pattern.compile("^\\s+'([^']+)':\\s*$");
    private static final Pattern STATUS = Pattern.compile("^\\s+statusCode:\\s*'(\\d{3})'\\s*$");

    private final DirectOrderHandlers handlers = new DirectOrderHandlers(OrdersApplication.createService());

    @Test
    void missingOrderSelects404() throws IOException {
        assertStatus("GetOrder", 404, () -> handlers.getOrder(new OrderIdInput("ORD-999")),
                "Order ORD-999 does not exist");
    }

    @Test
    void blankIdSelects400() throws IOException {
        assertStatus("GetOrder", 400, () -> handlers.getOrder(new OrderIdInput(" ")), null);
    }

    @Test
    void invalidQuantitySelects400() throws IOException {
        assertStatus("CreateOrder", 400,
                () -> handlers.createOrder(new CreateOrderRequest("CUST-42", "MacBook Air", 0)),
                "quantity must be greater than 0");
    }

    @Test
    void missingCustomerIdSelects400() throws IOException {
        assertStatus("FindOrders", 400, () -> handlers.getOrdersByCustomer(new CustomerQueryInput(null)), null);
    }

    @Test
    void unexpectedFailureSelects500() throws IOException {
        OrderService broken = new OrderService(new InMemoryOrderRepository(), () -> {
            throw new IllegalStateException("id generator exploded");
        });
        DirectOrderHandlers brokenHandlers = new DirectOrderHandlers(broken);

        assertStatus("CreateOrder", 500,
                () -> brokenHandlers.createOrder(new CreateOrderRequest("CUST-1", "A", 1)),
                "Unexpected internal error");
    }

    @Test
    void academyTemplateHasTheSameErrorMappings() throws IOException {
        assertEquals(mappings(Path.of("template.yaml")), mappings(Path.of("template-academy.yaml")));
    }

    /** Lanza el error real y comprueba que exactamente una regex lo selecciona, con el estado esperado. */
    private static void assertStatus(String function, int expected, Executable call, String domainMessage)
            throws IOException {
        Map<String, Integer> patterns = mappings(Path.of("template.yaml")).get(function);
        assertFalse(patterns == null || patterns.isEmpty(), "no error mappings for " + function);

        String errorMessage = assertThrows(DirectInvocationException.class, call).getMessage();
        List<Integer> selected = patterns.entrySet().stream()
                .filter(e -> Pattern.matches(e.getKey(), errorMessage))
                .map(Map.Entry::getValue)
                .toList();

        assertEquals(List.of(expected), selected, function + " <- \"" + errorMessage + "\"");

        // Mismo cálculo que el template VTL: $raw.substring($raw.indexOf(': ') + 2)
        String extracted = errorMessage.substring(errorMessage.indexOf(": ") + 2);
        if (domainMessage != null) {
            assertEquals(domainMessage, extracted);
        }
        assertTrue(!extracted.isBlank() && !extracted.contains("Exception"), extracted);
    }

    /** Función de la arquitectura A → (selection pattern → estado HTTP), leído de la plantilla. */
    private static Map<String, Map<String, Integer>> mappings(Path template) throws IOException {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(template);
        String function = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("OrdersProxyFunction:")) {
                break; // termina la arquitectura A
            }
            Matcher uri = URI.matcher(line);
            if (uri.find()) {
                function = uri.group(1);
                result.put(function, new LinkedHashMap<>());
                continue;
            }
            Matcher selection = SELECTION.matcher(line);
            if (function != null && selection.matches() && i + 1 < lines.size()) {
                Matcher status = STATUS.matcher(lines.get(i + 1));
                if (status.matches()) {
                    result.get(function).put(selection.group(1), Integer.parseInt(status.group(1)));
                }
            }
        }
        return result;
    }
}
