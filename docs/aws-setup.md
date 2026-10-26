# Reproducing the AWS setup

Everything is built from one JAR: `target/orders-api-1.0.0.jar` (`mvn package`).

There are two ways to reproduce the deployment:

* **Option 1 — SAM** (`template.yaml`, one command, recommended for repeatability).
* **Option 2 — AWS Console** (step by step, recommended the first time, so you *see* each piece).

> Region used in the examples: `us-east-1`. Replace `<api-id>` with your API id.

---

## Option 1 — AWS SAM

Prerequisites: AWS CLI v2 configured (`aws configure`), AWS SAM CLI, Java 17+, Maven.

```bash
mvn clean package
sam deploy --guided          # first time: stack name e.g. orders-api, allow IAM role creation
# later deployments
mvn package && sam deploy
```

The stack outputs two base URLs:

| Output | Architecture |
|---|---|
| `MappedApiUrl` | A — non-proxy integration + mapping templates, one Lambda per operation |
| `ProxyApiUrl`  | B — Lambda proxy integration, one Lambda with the HTTP adapter |

Delete everything with `sam delete`.

### Option 1b — AWS Academy Learner Lab (what was actually deployed)

The lab cannot create IAM roles and its CloudShell may have no credentials, so use
`template-academy.yaml` (same resources; code from S3, every function uses `LabRole`):

1. `mvn clean package`, then upload `target/orders-api-1.0.0.jar` to a private S3 bucket.
2. CloudFormation → **Create stack** → *Upload a template file* → `template-academy.yaml`.
3. Stack name `orders-api`, parameter `CodeBucket` = your bucket (`CodeKey` defaults to the JAR name).
4. Acknowledge the capabilities (`CAPABILITY_AUTO_EXPAND` is required by the SAM transform) → **Submit**.
5. The **Outputs** tab shows `MappedApiUrl` and `ProxyApiUrl`.

Delete the stack (and empty the bucket) when finished.

---

## Option 2 — AWS Console

### Part II — a Lambda invoked directly (no API Gateway)

1. **Lambda → Create function** → *Author from scratch*
   * Name: `orders-a-create-order`
   * Runtime: **Java 17**
2. **Code → Upload from → .zip or .jar file** → `target/orders-api-1.0.0.jar`
3. **Runtime settings → Edit → Handler**:
   `edu.eci.tdse.orders.lambda.direct.DirectOrderHandlers::createOrder`
4. **Configuration → General**: memory 512 MB, timeout 15 s (the first, *cold*, Java invocation is slow).
5. **Test** tab → new event, paste `events/direct-create-order.json` → *Test*.
   Repeat with `events/direct-create-order-2.json` and `events/direct-create-order-invalid.json`.

With the AWS CLI instead of the console:

```bash
aws lambda invoke --function-name orders-a-create-order \
  --cli-binary-format raw-in-base64-out \
  --payload file://events/direct-create-order.json out.json && cat out.json
```

Create the other two functions the same way, with the same JAR:

| Function | Handler |
|---|---|
| `orders-a-get-order` | `edu.eci.tdse.orders.lambda.direct.DirectOrderHandlers::getOrder` |
| `orders-a-find-orders` | `edu.eci.tdse.orders.lambda.direct.DirectOrderHandlers::getOrdersByCustomer` |

### Part IV — Architecture A: REST API with mapping templates

**API Gateway → Create API → REST API → Build**, name `orders-api-mapping`.

Create resources `/orders` and `/orders/{id}`, then the methods below. For every method choose
**Integration type: Lambda function** and leave **Lambda proxy integration OFF**.

#### `GET /orders/{id}` → `orders-a-get-order`

*Integration request → Mapping templates* → *When there are no templates defined* →
content type `application/json`:

```velocity
{"id": "$util.escapeJavaScript($input.params('id'))"}
```

*Method response*: add status codes `200`, `400`, `404`, `500`.

*Integration response* (one per row; *Lambda error regex* is matched against `errorMessage`):

| Lambda error regex | Method status | Mapping template (`application/json`) |
|---|---|---|
| *(default — no regex)* | 200 | *(none, passthrough)* |
| `ORDER_NOT_FOUND:.*` | 404 | error template below with `ORDER_NOT_FOUND` |
| `VALIDATION_ERROR:.*` | 400 | error template below with `VALIDATION_ERROR` |
| `INTERNAL_ERROR:.*` | 500 | `{"error": "INTERNAL_ERROR", "message": "Unexpected internal error"}` |

Error template (turns `"ORDER_NOT_FOUND: Order ORD-999 does not exist"` into the error JSON):

```velocity
#set($raw = $input.path('$.errorMessage'))
#set($start = $raw.indexOf(': ') + 2)
{"error": "ORDER_NOT_FOUND", "message": "$util.escapeJavaScript($raw.substring($start))"}
```

#### `GET /orders` → `orders-a-find-orders`

*Method request → URL query string parameters*: `customerId`, **Required** ✔; *Request validator*:
*Validate query string parameters and headers*.

Mapping template:

```velocity
{"customerId": "$util.escapeJavaScript($input.params('customerId'))"}
```

Integration responses: default → 200, `VALIDATION_ERROR:.*` → 400, `INTERNAL_ERROR:.*` → 500.

#### `POST /orders` → `orders-a-create-order`

*Models → Create* `CreateOrderRequest` (`application/json`):

```json
{
  "$schema": "http://json-schema.org/draft-04/schema#",
  "type": "object",
  "required": ["customerId", "product", "quantity"],
  "properties": {
    "customerId": {"type": "string"},
    "product":    {"type": "string"},
    "quantity":   {"type": "integer"}
  }
}
```

*Method request*: request validator *Validate body*, request body `application/json` →
`CreateOrderRequest`.

*Integration request* → passthrough *Never*, template for `application/json`:

```velocity
$input.json('$')
```

*Method response*: `201` (add header `Location`), `400`, `500`.

*Integration response*:

| Lambda error regex | Status | Template |
|---|---|---|
| *(default)* | **201** | `#set($context.responseOverride.header.Location = "/orders/$input.path('$.id')")`<br>`$input.json('$')` |
| `VALIDATION_ERROR:.*` | 400 | error template with `VALIDATION_ERROR` |
| `INTERNAL_ERROR:.*` | 500 | internal error template |

#### Gateway responses (errors that API Gateway produces before calling Lambda)

| Gateway response | Status | Template |
|---|---|---|
| Bad Request Body | 400 | `{"error": "VALIDATION_ERROR", "message": $context.error.messageString}` |
| Bad Request Parameters | 400 | `{"error": "VALIDATION_ERROR", "message": $context.error.messageString}` |
| Missing Authentication Token (= unknown route) | 404 | `{"error": "ROUTE_NOT_FOUND", "message": "No such resource or method"}` |
| Default 5XX | — | `{"error": "INTERNAL_ERROR", "message": $context.error.messageString}` |

**Deploy API** → new stage `prod`.

### Part V — Architecture B: REST API with Lambda proxy integration

1. Create function `orders-b-proxy`, Java 17, same JAR, handler
   `edu.eci.tdse.orders.lambda.proxy.OrdersApiHandler::handleRequest`.
   Optional environment variable `LOG_EVENTS=true` to print every raw event to CloudWatch
   (that is how `events/proxy-*.json` can be captured from a real request).
2. New REST API `orders-api-proxy`, resources `/orders` and `/orders/{id}`.
3. Methods `POST /orders`, `GET /orders`, `GET /orders/{id}`, `DELETE /orders/{id}`: Lambda function `orders-b-proxy`,
   **Lambda proxy integration ON**. Nothing else: no models, no templates, no integration responses.
4. Optional: gateway response *Missing Authentication Token* → 404 with the `ROUTE_NOT_FOUND` body.
5. Deploy to stage `prod`.
