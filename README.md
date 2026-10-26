# Orders API — API REST serverless con AWS Lambda y API Gateway

Una pequeña API de gestión de pedidos en **Java 17 + Maven**, expuesta mediante **Amazon API
Gateway** con dos arquitecturas que comparten la misma lógica de aplicación en Java puro:

* **A — Mapping templates (integración sin proxy).** API Gateway extrae los parámetros, arma el
  JSON que recibe Lambda y convierte los errores en códigos de estado. Hay una Lambda por operación.
* **B — Integración Lambda proxy.** API Gateway reenvía la petición HTTP completa a una sola Lambda,
  y un adaptador HTTP delgado en Java se encarga del enrutamiento y de las respuestas HTTP.

| Operación | Petición | Éxito |
|---|---|---|
| Crear un pedido | `POST /orders` | `201 Created` + `Location` |
| Consultar un pedido | `GET /orders/{id}` | `200 OK` |
| Buscar pedidos de un cliente | `GET /orders?customerId={customerId}` | `200 OK` |
| Eliminar un pedido *(reto final, arquitectura B)* | `DELETE /orders/{id}` | `204 No Content` |

---

## Contenido

1. [Compilar y probar](#1-compilar-y-probar)
2. [Estructura del proyecto](#2-estructura-del-proyecto)
3. [Diseño de la API (contrato)](#3-diseño-de-la-api-contrato)
4. [Checkpoint 1 — Java puro](#4-checkpoint-1--java-puro)
5. [Parte II — Lambda y la conversión JSON ↔ Java](#5-parte-ii--lambda-y-la-conversión-json--java)
6. [Arquitectura A — mapping templates de API Gateway](#6-arquitectura-a--mapping-templates-de-api-gateway)
7. [Arquitectura B — integración Lambda proxy](#7-arquitectura-b--integración-lambda-proxy)
8. [Comparación de las dos arquitecturas](#8-comparación-de-las-dos-arquitecturas)
9. [Errores internos inesperados](#9-errores-internos-inesperados)
10. [Arquitectura final](#10-arquitectura-final)
11. [Desarrollo asistido por IA — crítica al agente](#11-desarrollo-asistido-por-ia--crítica-al-agente)
12. [Reto final — DELETE](#12-reto-final--delete)
13. [Checklist de la demostración](#13-checklist-de-la-demostración)
14. [Despliegue real y evidencias](#14-despliegue-real-y-evidencias)

Otros documentos:

* [`docs/aws-setup.md`](docs/aws-setup.md): cómo reproducir la configuración de AWS, con SAM o paso a paso en la consola.
* [`docs/examples.md`](docs/examples.md): ejemplos de peticiones y respuestas (curl).
* [`docs/delete-prediction.md`](docs/delete-prediction.md): predicción del reto DELETE y su resultado.
* [`template.yaml`](template.yaml): infraestructura como código de ambas arquitecturas, con todos los mapping templates.
* [`template-academy.yaml`](template-academy.yaml): la misma plantilla adaptada a AWS Academy (código desde S3 y rol `LabRole`); es la que se desplegó.
* [`docs/evidence/`](docs/evidence/): capturas del despliegue real (§14).
* [`events/`](events/): payloads de ejemplo. `direct-*.json` sirven para invocar las Lambdas directamente; `proxy-*.json` son eventos proxy de API Gateway.

---

## 1. Compilar y probar

Requisitos: JDK 17 o superior, Maven 3.9+.

```bash
mvn test        # pruebas unitarias: sin cuenta de AWS, sin red, sin servidor
mvn package     # target/orders-api-1.0.0.jar  (JAR "shaded" que se sube a Lambda)
```

El despliegue está en [`docs/aws-setup.md`](docs/aws-setup.md). En resumen:
`mvn package && sam deploy --guided`.

---

## 2. Estructura del proyecto

```
.
├── pom.xml
├── template.yaml                     # SAM: ambas APIs, todas las Lambdas, mapping templates
├── events/                           # entradas de ejemplo para Lambda (directas y proxy)
├── docs/
└── src/
    ├── main/java/edu/eci/tdse/orders/
    │   ├── domain/                   # ── Java puro, sin AWS ni HTTP ──
    │   │   ├── Order.java
    │   │   ├── CreateOrderRequest.java
    │   │   ├── OrderService.java     # crear / consultar / buscar por cliente / eliminar
    │   │   ├── OrderRepository.java  # interfaz de almacenamiento
    │   │   ├── InMemoryOrderRepository.java
    │   │   ├── OrdersApplication.java# armado del servicio + datos de ejemplo
    │   │   ├── OrderException.java   # base: lleva un código de error de negocio
    │   │   ├── OrderNotFoundException.java
    │   │   └── ValidationException.java
    │   └── lambda/
    │       ├── direct/               # handlers de la arquitectura A (POJO entra / POJO sale, sin librerías AWS)
    │       │   ├── DirectOrderHandlers.java
    │       │   ├── DirectInvocationException.java
    │       │   ├── OrderIdInput.java
    │       │   └── CustomerQueryInput.java
    │       └── proxy/                # adaptador HTTP de la arquitectura B (conoce API Gateway y HTTP)
    │           ├── OrdersApiHandler.java
    │           └── ErrorResponse.java
    └── test/java/edu/eci/tdse/orders/
        ├── ArchitectureTest.java     # falla si domain/ importa AWS, Jackson, servlets, Spring…
        ├── domain/OrderServiceTest.java
        ├── lambda/direct/DirectOrderHandlersTest.java
        └── lambda/proxy/OrdersApiHandlerTest.java
```

---

## 3. Diseño de la API (contrato)

El contrato se escribió antes que cualquier línea de código.

### Representaciones

```json
// Pedido
{ "id": "ORD-001", "customerId": "CUST-42", "product": "MacBook Air", "quantity": 1 }

// Error (todas las respuestas de error, sin importar qué componente las genere)
{ "error": "ORDER_NOT_FOUND", "message": "Order ORD-999 does not exist" }
```

`error` es un código estable que el cliente puede usar en su lógica, y `message` es una
explicación legible para personas. El estado HTTP no se repite en el body porque ya viene en la
línea de estado.

| Código `error` | HTTP | Significado |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Falta un campo o parámetro obligatorio, o se viola una regla de negocio |
| `INVALID_REQUEST_BODY` | 400 | El body no es JSON válido o tiene tipos incorrectos (B) |
| `ORDER_NOT_FOUND` | 404 | No existe un pedido con ese id |
| `ROUTE_NOT_FOUND` | 404 | No existe ese recurso o método |
| `METHOD_NOT_ALLOWED` | 405 | El recurso existe pero no con ese método (B, con header `Allow`) |
| `INTERNAL_ERROR` | 500 | Falla inesperada. El detalle va al log, nunca a la respuesta |

### Operaciones

| | `POST /orders` | `GET /orders/{id}` | `GET /orders?customerId=` | `DELETE /orders/{id}` |
|---|---|---|---|---|
| Parámetros de ruta | – | `id` (id del pedido) | – | `id` |
| Parámetros de query | – | – | `customerId` (**obligatorio**) | – |
| Body de la petición | `{"customerId","product","quantity"}` | – | – | – |
| Éxito | **201** + `Location: /orders/{nuevoId}` + el `Order` creado | **200** + `Order` | **200** + `Order[]` (puede ser `[]`) | **204**, sin body |
| Errores | 400 `VALIDATION_ERROR` / `INVALID_REQUEST_BODY`, 500 | 400 (id vacío), 404 `ORDER_NOT_FOUND`, 500 | 400 (falta `customerId`), 500 | 404 `ORDER_NOT_FOUND`, 500 |

Reglas de validación: `customerId` y `product` son obligatorios y no pueden estar vacíos;
`quantity` es un entero obligatorio ≥ 1. El `id` siempre lo asigna el servidor, así que si el
body del POST trae un `id`, se ignora.

### Preguntas

**¿Cuáles son los recursos de la API?**
Hay dos: la colección de pedidos, `/orders`, y cada pedido individual, `/orders/{id}`. El cliente
no es un recurso en esta API; es solo un atributo por el que se filtra (ver más abajo).

**¿Por qué `/orders/{id}` para consultar un pedido?**
En REST, una URL identifica un recurso. El id es lo que distingue un pedido de los demás, así que
va en la ruta, como hijo de la colección a la que pertenece. `GET` sobre esa URL lo lee, `DELETE`
sobre la misma URL lo elimina, y `POST /orders` devuelve esa URL en `Location`. La URL es
estable, se puede cachear y guardar, y no contiene verbos (`/getOrder?id=` sería estilo RPC).

**¿Por qué `customerId` es un parámetro de query en la búsqueda?**
La búsqueda no identifica un recurso nuevo; filtra la colección `/orders`. El resultado siguen
siendo "pedidos", solo que menos. Los parámetros de query son el lugar convencional para filtros
opcionales y combinables (`?customerId=…&product=…&page=2`). Un diseño como
`/customers/{id}/orders` tendría sentido solo si los clientes fueran recursos de primera clase en
esta API, y no lo son.

**¿Qué debe devolver `POST /orders` cuando el pedido se crea correctamente?**
`201 Created`, un header `Location: /orders/{id}` que apunta al nuevo recurso y el pedido creado
(con el `id` generado por el servidor) en el body. Así el cliente no necesita otra petición para
conocer el id.

**¿Qué debe devolver la API cuando un pedido no existe?**
`404 Not Found` con `{"error":"ORDER_NOT_FOUND","message":"Order ORD-999 does not exist"}`.
Esto es distinto de una búsqueda sin resultados: `GET /orders?customerId=CUST-NOBODY` devuelve
`200` y `[]`, porque la colección existe y la vista filtrada simplemente está vacía.

**¿Qué información debe contener una respuesta de error?**
Un código estable (`error`) y un `message` legible. No debe incluir stack traces, nombres de
clases de excepción ni otros detalles internos. El formato es el mismo para todos los errores,
los genere Java o API Gateway. En un sistema más grande agregaría también un id de petición o de
correlación (el `awsRequestId` de Lambda) para relacionar el reporte de un cliente con los logs.

---

## 4. Checkpoint 1 — Java puro

`OrderService` expone `createOrder(CreateOrderRequest)`, `getOrder(String)` y
`getOrdersByCustomer(String)`. Reporta las fallas con `OrderNotFoundException` y
`ValidationException`, cada una con un código de negocio. No aparece ningún estado HTTP.

| Pregunta | Respuesta |
|---|---|
| ¿Se puede probar la funcionalidad sin AWS? | **Sí.** `OrderServiceTest` cubre la creación de un pedido válido, la consulta de uno existente, un pedido inexistente, la búsqueda por cliente y entradas inválidas. Solo necesita la JVM y JUnit. |
| ¿Requiere un servidor web? | **No.** Nada escucha en un puerto; son llamadas normales a métodos Java. |
| ¿Requiere Spring Boot? | **No.** No hay ningún framework. `ArchitectureTest` hace fallar el build si `domain/` importa Spring, servlets, AWS o Jackson. |
| ¿Qué contiene el JAR generado? | `mvn package` usa el plugin Shade. `target/orders-api-1.0.0.jar` (≈3.4 MB) trae nuestras clases compiladas y todas las dependencias de ejecución: `aws-lambda-java-core`, `aws-lambda-java-events` y Jackson. Lambda necesita este único "uber JAR". `original-orders-api-1.0.0.jar` (≈20 KB) contiene solo nuestras clases. |
| ¿Qué partes conocen HTTP hasta ahora? | **Ninguna parte de la lógica de aplicación.** Solo `lambda/proxy/OrdersApiHandler` (arquitectura B) conoce métodos HTTP, códigos de estado y headers. En la arquitectura A, el conocimiento HTTP vive en la configuración de API Gateway, no en Java. |

---

## 5. Parte II — Lambda y la conversión JSON ↔ Java

`DirectOrderHandlers::createOrder` se despliega como la función `orders-a-create-order` y se
invoca directamente con `events/direct-create-order.json` y `events/direct-create-order-2.json`.
Los pasos están en [`docs/aws-setup.md`](docs/aws-setup.md#part-ii--a-lambda-invoked-directly-no-api-gateway).

`DirectOrderHandlers` **no** implementa `RequestHandler` y no importa nada de AWS. Es una clase
normal con métodos como `Order createOrder(CreateOrderRequest)`.

### ¿Cómo convierte el runtime de Java de Lambda el JSON en objetos Java y viceversa?

1. **El string del handler elige el método.** El valor
   `edu.eci.tdse.orders.lambda.direct.DirectOrderHandlers::createOrder` le indica al runtime qué
   clase instanciar y qué método llamar. La clase necesita un constructor público sin argumentos,
   y la instancia se reutiliza mientras el entorno de ejecución siga "caliente". No hace falta
   implementar `RequestHandler`, porque el runtime encuentra el método por reflexión. Se puede
   agregar un parámetro `Context` como segundo argumento.
2. **Entrada.** El runtime mira el tipo del parámetro del método y deserializa el payload en ese
   tipo con su serializador integrado (la librería `aws-lambda-java-serialization`, un Jackson
   empaquetado internamente):
   * POJO: se crea con el constructor sin argumentos y las propiedades del JSON se asignan con los
     setters correspondientes (`"customerId"` → `setCustomerId`).
   * Las propiedades que faltan en el JSON quedan en `null` o en su valor por defecto. Por eso
     `quantity` es `Integer`: así se distingue "no vino" de `0`.
   * Las propiedades del JSON que no tienen setter se ignoran.
   * También se soportan `String`, `Integer`, `Map`, `List` y las clases de eventos de AWS
     (`APIGatewayProxyRequestEvent`…), que tienen reglas de mapeo especiales.
   * Si el handler recibe `InputStream`/`OutputStream` (`RequestStreamHandler`), **no** hay
     conversión y uno mismo parsea los bytes.
3. **Salida.** El valor de retorno se serializa a JSON a través de sus **getters**
   (`getId()` → `"id"`). `void`/`null` producen `null`. Por eso `Order` es una clase con getters
   y no un record: los records exponen `id()` en lugar de `getId()`, y solo las versiones recientes
   del serializador del runtime los entienden.
4. **Excepciones.** Una excepción no capturada se convierte en un documento JSON de error
   `{"errorMessage": "...", "errorType": "<clase de la excepción>", "stackTrace": [...]}`, y la
   invocación queda marcada como *function error*. La arquitectura A depende de esto, porque API
   Gateway aplica sus regex sobre `errorMessage`.

### Verificación contra la Lambda desplegada

La explicación anterior vino del LLM. Estas invocaciones se ejecutaron el 2026-10-26 desde la
pestaña *Test* de la consola de Lambda (stack `orders-api` en `us-east-1`). La columna "Observado"
es lo que devolvió realmente la Lambda desplegada:

| Entrada | Esperado (según la explicación) | Observado | Evidencia |
|---|---|---|---|
| `direct-create-order.json` | `{"id":"ORD-XXXXXXXX","customerId":"CUST-42","product":"MacBook Air","quantity":1}` | ✅ `{"id":"ORD-D0CA1498","customerId":"CUST-42","product":"MacBook Air","quantity":1}` | [captura](docs/evidence/lambda-01-create-order-cust42.jpg) |
| `direct-create-order-2.json` | id nuevo, `CUST-100`, `quantity: 3` | ✅ `{"id":"ORD-6AEACE5E","customerId":"CUST-100","product":"Mechanical Keyboard","quantity":3}` | [captura](docs/evidence/lambda-02-create-order-cust100.jpg) |
| `{"customerId":"CUST-42","product":"Mouse","quantity":1,"color":"red"}` | funciona, `color` se ignora | ✅ `{"id":"ORD-AB67A9B9",…,"product":"Mouse","quantity":1}`, sin `color` y sin error | [captura](docs/evidence/lambda-03-unknown-field-ignored.jpg) |
| `{"customerId":"CUST-42","product":"Mouse"}` | error, `errorMessage: "VALIDATION_ERROR: quantity is required"` (lo que falta → `null`) | ✅ *function error*, `errorMessage: "VALIDATION_ERROR: quantity is required"` | [captura](docs/evidence/lambda-04-missing-quantity.jpg) |
| `{"customerId":"CUST-42","product":"Mouse","quantity":"2"}` | funciona, `"2"` se convierte en `2` | ✅ `"quantity": 2` (número) en la respuesta | [captura](docs/evidence/lambda-05-string-quantity-coerced.jpg) |
| `direct-create-order-invalid.json` | `errorMessage: "VALIDATION_ERROR: quantity must be greater than 0"`, `errorType: ...DirectInvocationException` | ✅ exactamente eso, más `stackTrace` | [captura](docs/evidence/lambda-06-invalid-quantity.jpg) |
| invocar `orders-a-get-order` con `direct-get-order-missing.json` | `errorMessage: "ORDER_NOT_FOUND: Order ORD-999 does not exist"` | ✅ exactamente eso, `errorType: ...DirectInvocationException` | [captura](docs/evidence/lambda-07-get-order-missing.jpg) |

**Conclusión de la verificación.** La explicación del LLM coincidió en los siete casos. Un hallazgo
adicional, no planeado: la primera invocación salió por error con el evento de ejemplo
"Hello World" de la consola (`{"key1":"value1",...}`) y la respuesta fue
`VALIDATION_ERROR: customerId is required`. Esto confirma por otra vía la regla 2: las claves desconocidas
se ignoraron y todos los campos de `CreateOrderRequest` quedaron en `null`.

### Checkpoint 2 — el flujo real

```mermaid
flowchart TB
    J1["Payload JSON<br/>{customerId, product, quantity}"]
    RT1["Runtime Java de AWS Lambda<br/>(serializador Jackson integrado)"]
    O1["CreateOrderRequest<br/>(objeto Java)"]
    H["DirectOrderHandlers::createOrder"]
    S["OrderService.createOrder()"]
    O2["Order (objeto Java)"]
    RT2["Runtime Java de AWS Lambda"]
    J2["Resultado JSON<br/>{id, customerId, product, quantity}<br/>o {errorMessage, errorType, stackTrace}"]
    J1 --> RT1 -->|"setters"| O1 --> H --> S --> O2 --> RT2 -->|"getters"| J2
```

---

## 6. Arquitectura A — mapping templates de API Gateway

```mermaid
flowchart LR
    C[Cliente] -->|HTTP| AGW
    subgraph AGW["API Gateway (orders-api-mapping)"]
        direction TB
        V["validadores de petición<br/>+ modelo CreateOrderRequest"]
        RT["mapping templates (VTL)<br/>petición → JSON"]
        IR["integration responses<br/>regex sobre errorMessage → estado"]
    end
    AGW -->|"{id}"| L1["λ orders-a-get-order"]
    AGW -->|"{customerId}"| L2["λ orders-a-find-orders"]
    AGW -->|"body"| L3["λ orders-a-create-order"]
    L1 & L2 & L3 --> D["DirectOrderHandlers → OrderService"]
```

### `GET /orders/{id}`

El request template extrae `{id}` de la ruta:

```velocity
{"id": "$util.escapeJavaScript($input.params('id'))"}
```

así que `GET /orders/ORD-42` invoca `getOrder` con `{"id": "ORD-42"}`. El código Java nunca ve
la URL.

### `GET /orders?customerId=…`

`customerId` se declara como parámetro de query obligatorio, y el validador de peticiones
devuelve `400` antes de llamar a Lambda si falta. El template:

```velocity
{"customerId": "$util.escapeJavaScript($input.params('customerId'))"}
```

### `POST /orders`

API Gateway valida el body contra el modelo JSON Schema `CreateOrderRequest`, que solo revisa
campos obligatorios y tipos. Después el body pasa tal cual (`$input.json('$')`). La integration
response por defecto se cambia a **201**, y un template agrega el header `Location`:

```velocity
#set($context.responseOverride.header.Location = "/orders/$input.path('$.id')")
$input.json('$')
```

### Semántica HTTP: cómo los errores se vuelven códigos de estado

El handler Java señala los errores lanzando `DirectInvocationException` con el mensaje
`"<CODIGO>: <texto>"`. API Gateway aplica **Lambda error regexes** sobre `errorMessage`:

| Regex | Estado | Body generado por el template |
|---|---|---|
| *(por defecto)* | 200 / 201 | el resultado de la Lambda |
| `ORDER_NOT_FOUND:.*` | 404 | `{"error":"ORDER_NOT_FOUND","message":"Order ORD-999 does not exist"}` |
| `VALIDATION_ERROR:.*` | 400 | `{"error":"VALIDATION_ERROR","message":"quantity must be greater than 0"}` |
| `INTERNAL_ERROR:.*` | 500 | `{"error":"INTERNAL_ERROR","message":"Unexpected internal error"}` |

Los errores propios de API Gateway (body inválido, parámetro faltante, ruta desconocida) usan el
mismo formato JSON gracias a las **gateway responses**. La configuración completa está en
`template.yaml` (`MappedApi`) y en
[`docs/aws-setup.md`](docs/aws-setup.md#part-iv--architecture-a-rest-api-with-mapping-templates).

### Checkpoint 3 — de qué se encarga API Gateway en A

| Responsabilidad | Dónde vive en A |
|---|---|
| Enrutamiento (método + ruta → función) | API Gateway: cada método está conectado a su propia Lambda |
| Extraer parámetros de ruta | Mapping template de API Gateway (`$input.params('id')`) |
| Extraer parámetros de query | Mapping template + declaración de parámetro obligatorio |
| Validación estructural (campos obligatorios, tipos) | Validador de peticiones + modelo de API Gateway |
| Validación de negocio (`quantity ≥ 1`, no vacíos) | Java (`OrderService`) |
| Transformar peticiones | API Gateway (VTL arma la entrada de la Lambda) |
| Transformar respuestas | API Gateway (VTL arma el body de error y agrega `Location`) |
| Elegir códigos de estado | API Gateway (respuesta por defecto = 200/201, regex = 4xx/5xx) |
| Mapear errores | **Compartido.** Java decide el *código* (`ORDER_NOT_FOUND`); API Gateway lo convierte en estado y body |

**Ventajas**

* Los handlers Java son mínimos: sin HTTP y sin imports de AWS; cada uno es solo una llamada a
  un método.
* Las peticiones inválidas se rechazan *antes* de ejecutar una Lambda, lo que ahorra
  invocaciones y cold starts.
* El contrato de la API (parámetros, modelos, códigos de estado) se declara en un solo lugar que
  se puede exportar como OpenAPI/Swagger.
* Cada operación es su propia función, así que puede tener su propia memoria, timeout, permisos
  IAM y métricas.

**Desventajas**

* **El comportamiento queda repartido en dos lugares unidos por strings.** El prefijo
  `"ORDER_NOT_FOUND: "` en Java tiene que coincidir con una regex en API Gateway. Si alguien
  renombra el código en Java, el compilador y las pruebas unitarias pasan, y la API empieza a
  responder **200** con un body de error.
* **Las fallas que no coinciden con ninguna regex caen en la respuesta por defecto.** Eso es un
  200 (o 201) con `{"errorMessage": ...}`. Un timeout o un out-of-memory de Lambda produce un
  mensaje sin nuestro prefijo, así que termina exactamente en este caso.
* VTL es difícil de leer, depurar y probar, y nada de eso corre en `mvn test`. Un error de
  tipeo solo aparece después de desplegar.
* La configuración crece con cada operación × cada código de estado.
* Dependencia del proveedor: el comportamiento HTTP existe solo en la configuración propietaria
  de API Gateway.
* En este taller, cada función tiene su propio almacenamiento en memoria, así que **un pedido
  creado con el POST de A no se puede leer con el GET de A**: son JVMs distintas. Solo los
  pedidos precargados son visibles en todas las funciones. (B tiene el mismo problema entre cold
  starts e instancias concurrentes; ninguno de los dos almacenamientos es persistente.)

---

## 7. Arquitectura B — integración Lambda proxy

Con la integración proxy, API Gateway no transforma nada: envuelve la petición HTTP completa en
un evento, y la Lambda debe devolver `{statusCode, headers, body}`.

### Dónde aparece cada dato en el evento proxy

Ejemplo: [`events/proxy-get-order.json`](events/proxy-get-order.json); también `proxy-find-orders.json`, `proxy-create-order.json` y `proxy-delete-order.json`.

**Evento real capturado en AWS:** [`events/proxy-get-order-captured.json`](events/proxy-get-order-captured.json). Es el JSON exacto que API Gateway envió a `orders-b-proxy` para `GET /orders/ORD-001`, sacado del log de la pestaña *Test* de API Gateway (credenciales temporales y usuario ocultos). Ver §14.

| Información | Campo del evento (REST API, payload v1) | Ejemplo |
|---|---|---|
| Método HTTP | `httpMethod` (también `requestContext.httpMethod`) | `"GET"` |
| Ruta | `path` (real) / `resource` (plantilla) | `"/orders/ORD-42"` / `"/orders/{id}"` |
| Parámetros de ruta | `pathParameters` | `{"id": "ORD-42"}` |
| Parámetros de query | `queryStringParameters` (+ `multiValueQueryStringParameters`) | `{"customerId": "CUST-42"}` |
| Headers | `headers` (+ `multiValueHeaders`) | `{"Content-Type": "application/json", ...}` |
| Body de la petición | `body`: siempre un **string** (el JSON no viene parseado); ver `isBase64Encoded` | `"{\"customerId\":\"CUST-42\",...}"` |

Cuando un campo no está, llega como `null`, no vacío. Por ejemplo, `queryStringParameters` es
`null` si no hay query string, y el handler lo maneja. Con `LOG_EVENTS=true` en la función se
imprimen los eventos reales en CloudWatch.

### El adaptador HTTP

`OrdersApiHandler` es la **única** clase que conoce API Gateway y HTTP:

```mermaid
flowchart LR
    E["APIGatewayProxyRequestEvent"] --> R{"enrutar por<br/>resource + httpMethod"}
    R -->|"POST /orders"| P["parsear body → CreateOrderRequest"] --> S1["service.createOrder"]
    R -->|"GET /orders"| Q["queryStringParameters.customerId"] --> S2["service.getOrdersByCustomer"]
    R -->|"GET /orders/{id}"| PP["pathParameters.id"] --> S3["service.getOrder"]
    R -->|"DELETE /orders/{id}"| PD["pathParameters.id"] --> S4["service.deleteOrder"]
    S1 & S2 & S3 --> OK["200 / 201 + Location"]
    S4 --> NC["204 (sin body)"]
    S1 & S2 & S3 & S4 -.->|"OrderNotFoundException"| N["404"]
    S1 & S2 & S3 & S4 -.->|"ValidationException"| B["400"]
    S1 & S2 & S3 & S4 -.->|"cualquier otra"| I["500 (se registra en el log)"]
```

* El handler conoce el evento, el enrutamiento, los parámetros de ruta y query, el parseo JSON
  del body, los códigos de estado, los headers `Content-Type`, `Location` y `Allow`, y la
  serialización de la respuesta.
* La lógica de aplicación sigue trabajando solo con `Order`, `CreateOrderRequest`, ids de cliente
  y cantidades, igual que antes. **No cambió ninguna clase de `domain/`** para soportar B.

En el 201 se devuelve `Location: /orders/{id}`, que es la forma estándar (RFC 9110) de indicarle
al cliente la URL del recurso que acaba de crear. Es una URL relativa porque el handler no
conoce el stage ni el dominio personalizado por el que llamó el cliente.

Todo el comportamiento de B tiene pruebas unitarias locales (`OrdersApiHandlerTest`, 20 pruebas).
Las pruebas arman los eventos a mano y también cargan los `events/proxy-*.json` guardados.

---

## 8. Comparación de las dos arquitecturas

| Pregunta | A — transformación en API Gateway | B — proxy + adaptador Java |
|---|---|---|
| **¿Cuál requirió más configuración de API Gateway?** | **A, por mucho.** Por cada método: un request template, un modelo/validador, method responses, una integration response (regex + template) por cada estado, y además las gateway responses. Unas 200 líneas de `template.yaml`. | Por cada método: "Lambda proxy, activado". Unas 15 líneas. |
| **¿Cuál requirió más código Java de infraestructura?** | Muy poco: una clase POJO-entra/POJO-sale, dos DTOs de entrada y una excepción. Ninguna dependencia de AWS. | **B.** `OrdersApiHandler` (~200 líneas) hace enrutamiento, parseo, mapeo de estados y headers, y depende de `aws-lambda-java-events` + Jackson. |
| **¿Cuál fue más fácil de probar localmente?** | Las partes Java sí. Los templates VTL, las regex y los validadores **no** se pueden probar con `mvn test`; hay que desplegar (o usar el botón *Test* de la consola). | **B.** Todo el comportamiento HTTP (estados, headers, bodies de error, JSON mal formado) se prueba con JUnit en milisegundos. |
| **¿Dónde se definen los códigos de estado?** | En API Gateway: la integration response por defecto (200/201), las regex (400/404/500) y las gateway responses (validación). Java solo decide el *código* de error. | En Java, en un método (`statusFor`) más `ok`/`created` en el adaptador. API Gateway solo genera un estado para errores que ocurren antes de Lambda (ruta desconocida). |
| **¿Qué pasa si la API crece de 3 a 30 operaciones?** | La configuración crece a unos 30 × (template + respuestas × estados). Cada código de estado nuevo hay que agregarlo en cada método. Los errores de copiar y pegar se vuelven probables, y terminas con 30 funciones que desplegar y monitorear o con un truco de despacho. | Cada operación agrega una ruta y una llamada al servicio. Un `switch` escrito a mano se vuelve incómodo a esa escala, así que pasaría a una tabla de rutas o a una librería (p. ej. `aws-serverless-java-container`, funciones de Micronaut/Quarkus), o lo dividiría en unas pocas Lambdas por contexto. |
| **¿Cuál deja más limpia la lógica de negocio Java?** | **Igual: `domain/` es idéntico en ambas.** A tiene el *handler* más limpio (sin HTTP). | Igual. Lo HTTP queda contenido en una sola clase adaptadora. |
| **¿Cuál sería más fácil de entender para otro desarrollador?** | Más difícil. Para entender una petición hay que leer Java, un modelo JSON Schema, VTL y mapeos con regex, repartidos entre la consola o el YAML. | Más fácil. El camino de la petición se lee de arriba a abajo en un archivo Java, y se puede recorrer con un depurador. |
| **¿Cuál elegiría para un sistema más grande?** | Vale la pena para una API pública y estable, manejada por un equipo de plataforma, o cuando importan las funciones del gateway (validar antes de invocar, integraciones directas con servicios de AWS sin Lambda). | **Mi elección.** Integración proxy (o una HTTP API) con un adaptador delgado por contexto. Mantendría **opcionalmente la validación estructural en el gateway** (o en un validador basado en OpenAPI) como defensa en profundidad. La semántica HTTP queda en código versionado y probado, y el dominio queda limpio. |

Para mí el factor decisivo es **dónde se detectan los errores**. En A, un contrato roto entre Java
y el gateway (un código de error renombrado) es invisible hasta producción y falla *en silencio*
con un 200. En B, el mismo error rompe una prueba unitaria.

---

## 9. Errores internos inesperados

| | Qué pasa |
|---|---|
| **B** | Cualquier excepción que no sea `OrderException` se captura en `OrdersApiHandler`. El stack trace va a CloudWatch y el cliente recibe `500 {"error":"INTERNAL_ERROR","message":"Unexpected internal error"}` sin detalles internos (probado en JUnit y en la Lambda desplegada, ver abajo). Si la Lambda misma se cae o hace timeout, API Gateway devuelve `502`/`504` `{"message": "Internal server error"}`. |
| **A** | Las excepciones inesperadas se envuelven como `INTERNAL_ERROR: …` y se mapean a 500 por regex (probado del lado Java). Un **timeout o out-of-memory** de Lambda produce un `errorMessage` que no coincide con ninguna regex, así que API Gateway devuelve la **respuesta por defecto, 200**, con el error crudo. Es una debilidad real de A. Una regex comodín (`.+`) hacia 500 lo evitaría, pero es fácil olvidarla. |

**Evidencia en AWS (arquitectura B).** Para provocar un error realmente inesperado en la Lambda
desplegada, invoqué `orders-b-proxy` desde la consola con un evento proxy que declara
`"isBase64Encoded": true` pero cuyo `body` no es base64 válido. `Base64.getDecoder()` lanza una
`IllegalArgumentException`, que no es un error de dominio, y el `catch (RuntimeException e)` del
adaptador la convierte en 500. El cliente recibe solo el mensaje genérico y el detalle queda en
el log.

Este caso no se puede provocar con una petición HTTP normal: API Gateway solo envía cuerpos en
base64 si se configuran *binary media types*, y esta API no los tiene. Por eso se usó la
invocación directa.

| | |
|---|---|
| Evento de entrada (POST con `body` base64 inválido) | ![](docs/evidence/lambda-08-proxy-500-event.jpg) |
| Respuesta: `statusCode: 500`, `{"error":"INTERNAL_ERROR","message":"Unexpected internal error"}` | ![](docs/evidence/lambda-09-proxy-500-response.jpg) |
| Log de la Lambda: `Unexpected error: java.lang.IllegalArgumentException: Illegal base64 character 25` en `OrdersApiHandler.parseBody` | ![](docs/evidence/lambda-10-proxy-500-log.jpg) |

---

## 10. Arquitectura final

```mermaid
flowchart LR
    Client(["Cliente<br/>curl / Postman"])

    subgraph AWS["AWS — servicios administrados y código específico de AWS"]
        direction LR
        APIGW["Amazon API Gateway<br/>REST API orders-api-proxy<br/>rutas: POST /orders, GET /orders,<br/>GET /orders/{id}, DELETE /orders/{id}<br/>(integración Lambda proxy)"]
        subgraph LAMBDA["AWS Lambda: orders-b-proxy (Java 17)"]
            direction TB
            H["Adaptador HTTP<br/>OrdersApiHandler<br/>depende de aws-lambda-java-events y Jackson"]
            subgraph PURE["Java puro (sin imports de AWS ni HTTP)"]
                SVC["OrderService<br/>(lógica de aplicación)"]
                MODEL["Order, CreateOrderRequest,<br/>OrderNotFoundException, ValidationException"]
                REPO["OrderRepository<br/>→ InMemoryOrderRepository"]
            end
        end
        CW[("CloudWatch Logs")]
    end

    Client -- "1 petición HTTP" --> APIGW
    APIGW -- "2 evento proxy (JSON)" --> H
    H -- "3 llamada Java" --> SVC
    SVC --> REPO
    SVC -. usa .-> MODEL
    SVC -- "4 Order / excepción" --> H
    H -- "5 {statusCode, headers, body}" --> APIGW
    APIGW -- "6 respuesta HTTP" --> Client
    H -. logs .-> CW

    classDef aws fill:#ff9900,stroke:#b36b00,color:#000
    classDef java fill:#5382a1,stroke:#2c4a63,color:#fff
    classDef adapter fill:#f5d76e,stroke:#b3962e,color:#000
    class APIGW,CW aws
    class H adapter
    class SVC,MODEL,REPO java
```

Leyenda: **naranja** son servicios administrados de AWS, **amarillo** es Java específico de AWS
(el adaptador) y **azul** es Java puro que corre y se prueba sin AWS. Las dependencias apuntan
hacia adentro: el adaptador depende del dominio, y el dominio no depende de nada.

---

## 11. Desarrollo asistido por IA — crítica al agente

Este proyecto se construyó con un agente de programación con IA. Se le pidió
proponer la arquitectura antes de escribir código. A continuación se analizan sus decisiones no
triviales.


### Decisión 1 — una Lambda por operación en A, una Lambda para toda la API en B

* **Decisión del agente:** la arquitectura A tiene tres funciones (`orders-a-create-order`,
  `-get-order`, `-find-orders`), cada una apuntando a un método distinto de la misma clase. La
  arquitectura B tiene una sola función.
* **¿Por qué creo que lo propuso?** En la integración sin proxy, cada método de API Gateway se
  conecta a una función, y el mapping template produce exactamente el tipo de entrada de esa
  función. Una sola función necesitaría un campo extra `"operation"` y un despachador, lo que
  vuelve a meter el enrutamiento en Java. Con proxy, el handler ya recibe el método y la ruta, así
  que una sola función es lo natural.
* **¿Estoy de acuerdo?** En parte. Para el taller tiene sentido, porque muestra cada estilo como
  se usa normalmente. Pero el agente no advirtió la consecuencia más importante hasta que la vi en
  el despliegue real: con almacenamiento en memoria, **el POST de A crea pedidos que el GET de A
  nunca ve**, porque cada función corre en su propia JVM. Lo comprobé en AWS (§14): el pedido
  creado por `orders-a-create-order` no aparece en `GET /orders?customerId=CUST-42`. En B sí
  aparece, pero solo porque una única Lambda atiende todo y mientras siga caliente. Para mí esto
  muestra que la decisión de "una Lambda por operación" no se puede separar de la decisión de
  persistencia: con varias funciones, la base de datos compartida es obligatoria desde el día uno.
* **Alternativa:** una sola Lambda para A, con el nombre de la operación inyectado por cada
  mapping template (`{"operation":"get","id":...}`), o DynamoDB detrás de ambas.
* **¿Qué implementé y por qué?** La propuesta del agente, con la limitación documentada (§6). La
  demostración de crear y luego consultar usa la arquitectura B.

### Decisión 2 — códigos de error en el dominio, mapeo a estados HTTP en los adaptadores

* **Decisión del agente:** las excepciones del dominio llevan un código de negocio
  (`ORDER_NOT_FOUND`, `VALIDATION_ERROR`) pero ningún estado HTTP. B mapea tipo de excepción →
  estado en `statusFor()`. A pone el código como prefijo del mensaje (`"ORDER_NOT_FOUND: …"`) para
  que lo detecten las regex de API Gateway.
* **¿Por qué creo que lo propuso?** Mantiene `domain/` libre de HTTP. Un código de estado es un
  asunto del transporte, así que el mismo servicio podría estar detrás de un CLI o de una cola. El
  prefijo existe porque, en la integración sin proxy, `errorMessage` es *lo único* sobre lo que
  API Gateway puede seleccionar.
* **¿Estoy de acuerdo?** Sí con la separación dominio/adaptador: el dominio habla de pedidos y
  el adaptador traduce a HTTP. Con lo que no estoy cómoda es con el contrato por prefijo de A. Es
  un protocolo de strings entre Java y VTL que ningún compilador verifica: si alguien cambia el
  texto `"ORDER_NOT_FOUND: "` en Java, la regex deja de coincidir y API Gateway devuelve un 200
  con el error crudo. Las pruebas de `DirectOrderHandlersTest` protegen el lado Java, pero el lado
  de las regex solo lo pude verificar desplegando y probando en la pestaña *Test* de API Gateway.
  En B el mismo mapeo es un `switch` en Java que sí se prueba localmente, y eso para mí pesa más.
* **Alternativa:** poner `httpStatus` directamente en las excepciones del dominio (más simple,
  pero mete HTTP en el dominio). O, en A, devolver un sobre `{status, body}` en lugar de lanzar
  excepciones y mapearlo con un template. Eso elimina las regex, pero hace que los handlers Java
  conozcan HTTP, y en ese punto ya estás a medio camino de una integración proxy.
* **¿Qué implementé y por qué?** La propuesta del agente, más `ArchitectureTest` para garantizar
  que `domain/` siga sin imports de AWS/HTTP.

### Decisión 3 — dónde vive la validación

* **Decisión del agente:** las reglas de negocio (`customerId`/`product` no vacíos,
  `quantity ≥ 1`) se validan en `OrderService`. Los problemas sintácticos (JSON mal formado,
  `quantity: "many"`, `quantity: 1.5`) los maneja el adaptador en B (`INVALID_REQUEST_BODY`). En A,
  el modelo de API Gateway solo revisa campos obligatorios y tipos.
* **¿Por qué creo que lo propuso?** Si las reglas de negocio vivieran solo en API Gateway (p. ej.
  `minimum: 1` en el JSON Schema), B y cualquier otro punto de entrada tendrían que duplicarlas, y
  las pruebas en Java puro no las cubrirían. Al dejarlas en el servicio, todas las arquitecturas
  las aplican.
* **¿Estoy de acuerdo?** Sí. Las reglas de negocio deben vivir en un solo lugar, y ese lugar es el
  servicio, porque es lo único que comparten A y B. El despliegue mostró un efecto secundario que
  no esperaba: en A un `POST` sin `quantity` lo rechaza el validador de API Gateway con un mensaje
  genérico (`"Invalid request body"`), mientras que en B el mismo error llega con el mensaje preciso
  del dominio (`"quantity is required"`). Es decir, validar en la puerta de entrada tiene un costo
  en la calidad de los mensajes de error. También me pareció un buen hallazgo que el agente
  desactivara `ACCEPT_FLOAT_AS_INT` de Jackson; sin eso, `"quantity": 1.9` se convertiría en `1`
  sin avisar.
* **Alternativa:** Bean Validation (anotaciones `jakarta.validation`) en `CreateOrderRequest`. Es
  declarativo y estándar, pero agrega una dependencia al dominio por tres reglas.
* **¿Qué implementé y por qué?** La propuesta del agente. La duplicación en A (el gateway revisa
  tipos, Java revisa reglas) es una defensa en profundidad deliberada.

### Decisión 4 — ids aleatorios generados por el servidor en lugar de una secuencia

* **Decisión del agente:** los ids son `ORD-` + 8 caracteres hexadecimales aleatorios (de un UUID),
  no `ORD-001, ORD-002…`. El generador se puede inyectar para las pruebas.
* **¿Por qué creo que lo propuso?** Un contador en memoria vuelve a 1 en cada cold start y en cada
  entorno de ejecución concurrente, así que entregaría ids duplicados.
* **¿Estoy de acuerdo?** Sí. Un contador en memoria es incorrecto en Lambda por la misma razón que
  la Decisión 1: cada entorno de ejecución tiene su propia memoria, así que dos instancias darían
  el mismo `ORD-001`. Mi única objeción es la longitud: 8 caracteres hexadecimales son unos 4 mil
  millones de valores, suficientes para un taller pero no para un sistema real, donde usaría el
  UUID completo o dejaría que la base de datos genere la clave.
* **¿Qué implementé?** La propuesta del agente, aceptando esa limitación de forma consciente.

### Decisión 5 — desplegar con `sam deploy` y roles IAM creados por SAM *(no la seguí)*

* **Decisión del agente:** `template.yaml` deja que SAM cree un rol IAM por función, y el plan de
  despliegue era `sam deploy --guided` desde una terminal con credenciales (CloudShell). Cuando el
  laboratorio impidió crear roles, su primera corrección fue declarar `Role: LabRole` dentro de
  `Globals`.
* **¿Por qué creo que lo propuso?** Es el camino estándar de SAM en una cuenta normal: un comando,
  roles con mínimo privilegio y nada manual. El agente asumió una cuenta con permisos completos.
* **¿Estoy de acuerdo?** No, para este entorno. En AWS Academy, CloudShell no obtenía credenciales
  y la cuenta no permite crear roles IAM, así que el plan no podía funcionar. La corrección en
  `Globals` además falló al desplegar: CloudFormation respondió
  `'Role' is not a supported property of 'Function'` dentro de `Globals`. El agente propuso una
  corrección sin verificar que SAM la aceptara.
* **Alternativa:** pedir permisos de IAM o usar otra cuenta. Ninguna era posible en el laboratorio.
* **¿Qué implementé y por qué?** Algo distinto. Dejé `template.yaml` intacto como versión
  "ideal" y creé `template-academy.yaml`: el código se toma de un bucket S3 (parámetros
  `CodeBucket`/`CodeKey`) y **cada función** declara el rol existente `LabRole`. Lo desplegué desde
  la consola de CloudFormation. Así el repositorio sirve para una cuenta normal y para el
  laboratorio, y la diferencia entre los dos archivos se limita a esas líneas.

### Decisión 6 — eventos proxy de ejemplo escritos a mano *(no la seguí)*

* **Decisión del agente:** para la Parte V generó `events/proxy-*.json` a mano, con valores
  inventados (`abc123xyz`, `123456789012`), y describió el evento a partir de la documentación.
* **¿Por qué creo que lo propuso?** Sin un despliegue todavía, era la única forma de tener eventos
  para las pruebas unitarias del adaptador.
* **¿Estoy de acuerdo?** Solo como punto de partida. El taller pide *investigar* el evento que
  envía API Gateway, y un evento escrito por la IA no demuestra nada: si el agente se equivocara en
  la estructura, las pruebas pasarían igual porque usan el mismo supuesto.
* **Alternativa:** activar `LOG_EVENTS=true` y leer el evento en CloudWatch, o usar la pestaña
  *Test* de API Gateway, que muestra el body enviado a Lambda.
* **¿Qué implementé y por qué?** Capturé el evento real con la pestaña *Test* de API Gateway y lo
  guardé en `events/proxy-get-order-captured.json` (§14), ocultando credenciales temporales y
  usuario. Al compararlo, la estructura escrita por el agente era correcta en `path`,
  `httpMethod`, `pathParameters`, `queryStringParameters` y `body`. El evento real agrega campos
  de `requestContext.identity` que el ejemplo no tenía, y en una prueba de consola el `stage` es
  `test-invoke-stage`. Mantengo los ejemplos manuales para las pruebas unitarias, pero la evidencia
  de la Parte V es el evento capturado.

---

## 12. Reto final — DELETE

La predicción se hizo commit **antes** de la implementación, en su propio commit:
[`docs/delete-prediction.md`](docs/delete-prediction.md). Ese mismo archivo tiene una sección
"Outcome" que compara la predicción con lo que realmente cambió.

En resumen, DELETE se agregó solo a la arquitectura B, la API final. **No necesitó una Lambda
nueva**; requirió un método nuevo en API Gateway (evento `DeleteOrder` en `template.yaml`),
`OrderService.deleteOrder` más `OrderRepository.deleteById` para la lógica, y un
`case "DELETE"` en el adaptador que devuelve 204. El 404 para un pedido inexistente reutiliza el
mapeo existente `OrderNotFoundException` → 404, así que no se escribió manejo de errores nuevo. Un
segundo DELETE del mismo pedido devuelve 404, porque DELETE es idempotente en el *estado* del
servidor, no en el código de respuesta.

---

## 13. Checklist de la demostración

Video de la demostración (2:13): [`docs/demo.mp4`](docs/demo.mp4). Recorre los puntos en este
orden, con un subtítulo en cada escena:

1. `mvn test`: 47 pruebas, todas en verde.
2. `mvn package`: mostrar `target/orders-api-1.0.0.jar`.
3. Invocar una Lambda directamente: *Test* en la consola con `events/direct-create-order.json` (o `aws lambda invoke`).
4. Llamar a la API desplegada (comandos en [`docs/examples.md`](docs/examples.md)):
   POST exitoso (201 + `Location`), GET exitoso (200), 404 (`ORD-999`), 400 (`quantity: 0`).
5. DELETE: `DELETE /orders/ORD-42` → 204, otra vez → 404, `GET /orders/ORD-42` → 404.
6. Error interno inesperado: invocación de `orders-b-proxy` → 500 `INTERNAL_ERROR` (§9).
7. Del path a la Lambda: pestaña *Test* de API Gateway en A y en B (§14).
8. Mostrar el diagrama de arquitectura (§10).

---

## 14. Despliegue real y evidencias

Ambas arquitecturas se desplegaron el **2026-10-26** en una cuenta de **AWS Academy Learner Lab**
(`us-east-1`), como un solo stack de CloudFormation llamado `orders-api`:

| Salida | URL base |
|---|---|
| A — mapping templates (`MappedApiUrl`) | `https://iykcgamnof.execute-api.us-east-1.amazonaws.com/prod` |
| B — proxy, API final (`ProxyApiUrl`) | `https://6j1hv8udp0.execute-api.us-east-1.amazonaws.com/prod` |

> Las URLs dejan de funcionar cuando la sesión del laboratorio termina o se borra el stack.

**Adaptaciones obligadas por el laboratorio.** `sam deploy` no se pudo usar tal cual:

1. CloudShell no obtenía credenciales (`Error retrieving credentials from container-role`), así
   que no había CLI dentro de AWS.
2. El Learner Lab no permite crear roles IAM, y SAM crea uno por función.

Por eso se agregó [`template-academy.yaml`](template-academy.yaml). Es idéntica a `template.yaml`
salvo en dos cosas: el código viene de un bucket S3 (parámetros `CodeBucket`/`CodeKey`) y cada
función usa el rol existente `LabRole`. El despliegue se hizo desde la consola:

1. `mvn package` y subir `target/orders-api-1.0.0.jar` a un bucket S3 privado.
2. CloudFormation → *Create stack* → subir `template-academy.yaml` → `CodeBucket` = nombre del bucket.
3. Aceptar `CAPABILITY_AUTO_EXPAND`, necesario por el `Transform` de SAM.

Un primer intento falló porque `Role` no se acepta dentro de `Globals` en SAM
(`'Role' is not a supported property of 'Function'`). Por eso el rol se declara en cada función.

### Infraestructura creada

| | |
|---|---|
| Recursos del stack: 4 Lambdas, 2 REST APIs, permisos y deployments | ![](docs/evidence/aws-01-cloudformation-resources.jpg) |
| Salidas del stack: las dos URLs base | ![](docs/evidence/aws-02-cloudformation-outputs.jpg) |
| A: recursos y métodos de `orders-api-mapping` | ![](docs/evidence/apigw-a-01-resources.jpg) |
| A: `GET /orders/{id}`, integración Lambda no proxy | ![](docs/evidence/apigw-a-02-get-order-method-execution.jpg) |
| A: mapping template de la petición, que extrae `{id}` del path | ![](docs/evidence/apigw-a-03-get-order-request-mapping-template.jpg) |
| A: integration response, regex `ORDER_NOT_FOUND:.*` → 404 | ![](docs/evidence/apigw-a-04-get-order-integration-response-404.jpg) |
| B: `orders-api-proxy` con `DELETE /orders/{id}` e integración *Proxy* | ![](docs/evidence/apigw-b-01-proxy-resources-delete.jpg) |

### Del path HTTP a la Lambda: pestaña *Test* de API Gateway

La pestaña *Test* de cada método muestra el path pedido, cómo lo interpreta API Gateway y el JSON
exacto que recibe la Lambda (`Endpoint request body after transformations`). Aquí se ve la
diferencia real entre las dos arquitecturas.

En los logs se ocultaron (`[REDACTED]`) el `X-Amz-Security-Token`, la firma `Authorization`, el
`accessKey` temporal y el usuario del laboratorio.

| Petición | Lo que muestra el log | Captura |
|---|---|---|
| **A** `GET /orders/ORD-42` | `Resource Path: /orders/ORD-42` → `Method request path: {id=ORD-42}` | ![](docs/evidence/apigw-a-05-test-get-order-path.jpg) |
| **A** `GET /orders/ORD-42` | El mapping template convierte la petición en `{"id": "ORD-42"}`, lo único que recibe `orders-a-get-order` | ![](docs/evidence/apigw-a-06-test-get-order-transformed-body.jpg) |
| **A** `GET /orders?customerId=CUST-42` | `Method request query string: {customerId=CUST-42}` | ![](docs/evidence/apigw-a-07-test-find-orders-query.jpg) |
| **A** `GET /orders?customerId=CUST-42` | Body transformado `{"customerId": "CUST-42"}` hacia `orders-a-find-orders` | ![](docs/evidence/apigw-a-08-test-find-orders-transformed-body.jpg) |
| **B** `GET /orders/ORD-001` | Mismo path y `{id=ORD-001}`, pero sin transformación | ![](docs/evidence/apigw-b-02-test-get-order-path.jpg) |
| **B** `GET /orders/ORD-001` | La Lambda recibe el **evento proxy completo**: `path`, `httpMethod`, `headers`, `pathParameters`, `queryStringParameters: null`, `body: null`. La respuesta es `{"statusCode":200,"headers":…,"body":"…"}` | ![](docs/evidence/apigw-b-03-test-get-order-proxy-event.jpg) |
| **B** `GET /orders?customerId=CUST-42` | En el evento proxy, `queryStringParameters: {"customerId":"CUST-42"}` y `pathParameters: null` | ![](docs/evidence/apigw-b-04-test-find-orders-query-proxy-event.jpg) |

En A, API Gateway extrae el dato y la Lambda solo ve `{"id": ...}`. En B, la Lambda recibe la
petición HTTP entera y es `OrdersApiHandler` quien lee `pathParameters` o `queryStringParameters`.
También se ve en la respuesta: en B la Lambda devuelve `statusCode`, `headers` y `body`; en A
devuelve el `Order` tal cual y API Gateway decide el código de estado.

### Invocación directa de Lambda (Parte II)

Las siete capturas `docs/evidence/lambda-0*.jpg` están enlazadas en la tabla de
[§5](#verificación-contra-la-lambda-desplegada).

### Llamadas HTTP a la API desplegada

Las capturas muestran las respuestas reales: código de estado, headers relevantes y cuerpo. Se
generaron con `fetch` desde el mismo origen de cada API, para poder mostrar en una sola página el
código, los headers y el cuerpo de cada respuesta.

**B — POST 201 + `Location`, GET 200, búsqueda por cliente, 404 y 400**

![](docs/evidence/http-b-01-post-get-404-400.jpg)

**B — DELETE: 204, luego 404 al repetir, 404 en GET y 404 en ruta desconocida**

![](docs/evidence/http-b-02-delete.jpg)

**A — las mismas operaciones con mapping templates**

![](docs/evidence/http-a-01-mapping-api.jpg)

### Lo que el despliegue real enseñó

* **La memoria de Lambda no es persistencia y además no se comparte.** En A, el pedido creado con
  `POST` (que corre en `orders-a-create-order`) **no aparece** en `GET /orders?customerId=CUST-42`,
  que corre en `orders-a-find-orders`. Cada función tiene su propio `InMemoryOrderRepository`. En B
  sí aparece, porque una sola Lambda atiende todas las rutas, pero solo mientras esa instancia
  siga caliente. Esto refuerza que un sistema real necesita un repositorio externo (por ejemplo
  DynamoDB) detrás de `OrderRepository`.
* **Los 400 de A no siempre vienen de Java.** Un `POST` sin `quantity` lo rechaza el validador de
  modelos de API Gateway (`"Invalid request body"`) antes de invocar la Lambda. Un `POST` con
  `quantity: 0` sí llega a Java y se mapea por regex (`"quantity must be greater than 0"`). En B,
  ambos mensajes vienen de la misma validación de dominio, así que son más precisos y consistentes.
* **DELETE solo existe en B.** En A, `DELETE /orders/{id}` devuelve el 404 `ROUTE_NOT_FOUND` de la
  gateway response.
