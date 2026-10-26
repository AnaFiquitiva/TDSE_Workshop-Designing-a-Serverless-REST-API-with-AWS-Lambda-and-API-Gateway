# Reto final — predicción para `DELETE /orders/{id}`

La predicción está en su propio commit (`8f8a502`), anterior al commit que implementa DELETE
(`14cdb04`). La sección "Resultado" se agregó después, para comparar la predicción con lo que el
cambio realmente requirió y con lo que hizo la API desplegada.

Comportamiento esperado: `DELETE /orders/ORD-42` → `204 No Content` (sin cuerpo);
`DELETE /orders/ORD-999` → `404 Not Found` con el cuerpo de error estándar.

| Pregunta | Predicción |
|---|---|
| **¿Qué clases Java deben cambiar?** | `OrderRepository` gana un `delete(id)` que indica si se borró algo, implementado en `InMemoryOrderRepository`. `OrderService` gana `deleteOrder(id)`, que lanza `OrderNotFoundException` si no se borró nada. `OrdersApiHandler` gana un caso `DELETE` en `/orders/{id}` que devuelve 204, y su header `Allow` pasa a `GET, DELETE`. **No** cambian `Order`, `CreateOrderRequest` ni las excepciones, porque el 404 ya existe como `OrderNotFoundException`. |
| **¿Se necesita una Lambda nueva?** | **No** en la arquitectura B (la API final): la misma `orders-b-proxy` atiende la ruta nueva. En la arquitectura A *sí* haría falta (`orders-a-delete-order`) más un método nuevo en `DirectOrderHandlers`, que es uno de los costos de escalar A. A no se extiende. |
| **¿Cambia la configuración de API Gateway?** | **Sí, poco.** API Gateway rechaza los métodos que no conoce antes de llamar a Lambda, así que hay que agregar un método `DELETE` en `/orders/{id}` con integración proxy (un `Events` nuevo en `template.yaml`). Nada más: ni templates ni mapeos de respuesta. |
| **¿Dónde vive la lógica de borrado?** | En `OrderService.deleteOrder` (lógica de aplicación), con el borrado efectivo en el repositorio. El adaptador solo extrae `id` y llama al servicio. |
| **¿Dónde se decide 204 o 404?** | Repartido. El **servicio** decide el *hecho* de que el pedido no existe (lanza `OrderNotFoundException`). El **adaptador HTTP** decide qué significa ese hecho en HTTP: éxito → 204 sin cuerpo, `OrderNotFoundException` → 404 (el mapeo que ya existía). El servicio no debe conocer los números 204/404. |
| **¿Qué pruebas se agregan?** | `OrderServiceTest`: borrar un pedido existente lo elimina (un `getOrder` posterior falla), borrar uno inexistente lanza `OrderNotFoundException`, un id vacío lanza `ValidationException`, y borrar un pedido no afecta los otros pedidos del cliente. `OrdersApiHandlerTest`: `DELETE` existente → 204 sin cuerpo; un `GET` después → 404; `DELETE` inexistente → 404 `ORDER_NOT_FOUND`; borrar dos veces → la segunda da 404; el header `Allow` incluye `DELETE`. |

Pregunta abierta: ¿un segundo DELETE del mismo pedido debe dar 404 o 204? DELETE es *idempotente*
en el **estado** del servidor (el pedido deja de existir en ambos casos), no en el código de
respuesta. El contrato del taller dice inexistente → 404, así que la segunda llamada da 404.

---

## Resultado (agregado después de implementar)

### Lo que el cambio tocó realmente

| Archivo | Cambio | ¿Predicho? |
|---|---|---|
| `domain/OrderRepository.java` | `boolean deleteById(String id)` | ✔ (cambia el nombre) |
| `domain/InMemoryOrderRepository.java` | `orders.remove(id) != null` | ✔ |
| `domain/OrderService.java` | `deleteOrder(id)`: valida el id y lanza `OrderNotFoundException` si no se borró nada | ✔ |
| `lambda/proxy/OrdersApiHandler.java` | `case "DELETE"` → `noContent()`; `Allow: GET, DELETE` | ✔ |
| `lambda/proxy/OrdersApiHandler.java` | helper nuevo `noContent()`: un 204 no lleva **cuerpo ni `Content-Type`**, así que no se podía reutilizar `jsonHeaders()` | ✘ no predicho |
| `template.yaml` | un evento `DeleteOrder` (`DELETE /orders/{id}`) en la misma función | ✔ |
| Pruebas | +5 en `OrderServiceTest`, +4 en `OrdersApiHandlerTest` | ✔ |
| `Order`, `CreateOrderRequest`, excepciones, `DirectOrderHandlers` | sin cambios | ✔ |

### Verificación contra la API desplegada (2026-10-26)

| Predicción | Lo que hizo AWS | Evidencia |
|---|---|---|
| DELETE existente → 204 sin cuerpo | `HTTP 204`, sin cuerpo y sin `Content-Type` | `docs/evidence/http-b-02-delete.jpg` |
| Segundo DELETE → 404 (idempotente en estado, no en código) | `HTTP 404 ORDER_NOT_FOUND` | misma captura |
| `GET` después de borrar → 404 | `HTTP 404 ORDER_NOT_FOUND` | misma captura |
| API Gateway rechaza métodos no declarados antes de llamar a Lambda | En la arquitectura A, que no tiene DELETE, `DELETE /orders/ORD-001` devuelve `404 ROUTE_NOT_FOUND` desde la *gateway response*, sin invocar ninguna Lambda | `docs/evidence/http-a-01-mapping-api.jpg` |
| No hace falta una Lambda nueva en B | La ruta `DELETE` aparece en `orders-api-proxy` integrada con la misma `orders-b-proxy` | `docs/evidence/apigw-b-01-proxy-resources-delete.jpg` |

### Comparación con la propuesta del agente y limitación

La predicción y la implementación se hicieron en la misma sesión de trabajo con el agente. Por eso
que coincidan en todos los puntos estructurales no prueba un criterio independiente: era
esperable. Lo que sí aporta este ejercicio es lo siguiente:

* **Un punto que nadie anticipó.** El helper `noContent()` existe porque un 204 con
  `Content-Type: application/json` y cuerpo vacío es un error de protocolo. La predicción no lo
  vio, y es el tipo de detalle que solo aparece al escribir la prueba ("204 sin cuerpo").
* **Una afirmación que se pudo comprobar en AWS.** "API Gateway rechaza los métodos que no conoce
  antes de llamar a Lambda" no venía del código, sino del conocimiento sobre API Gateway. El
  despliegue la confirmó: el DELETE en la arquitectura A nunca llega a Lambda.
* **El costo real del cambio en B:** tres líneas en el dominio, cinco en el adaptador, una ruta en
  el gateway y **ningún manejo de errores nuevo**, porque el 404 salió gratis del mapeo
  `OrderNotFoundException` → 404 que ya existía. En la arquitectura A la misma funcionalidad
  habría necesitado una Lambda nueva, un método nuevo en el handler, un mapping template, una
  method response para 204 e integration responses para 404, 400 y 500.
