# Example requests and responses

```bash
# Architecture B (proxy) — the final API
API=https://<proxy-api-id>.execute-api.us-east-1.amazonaws.com/prod
# Architecture A (mapping templates) — same requests work, except DELETE
# API=https://<mapped-api-id>.execute-api.us-east-1.amazonaws.com/prod
```

The repository is seeded with `ORD-001`, `ORD-002` (CUST-42), `ORD-003` (CUST-100) and
`ORD-42` (CUST-7). Use `curl -i` to see the status line and headers.

## 201 Created — `POST /orders`

```bash
curl -i -X POST "$API/orders" -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-42","product":"MacBook Air","quantity":1}'
```

```http
HTTP/2 201
content-type: application/json
location: /orders/ORD-1F3A9C2E

{"id":"ORD-1F3A9C2E","customerId":"CUST-42","product":"MacBook Air","quantity":1}
```

## 200 OK — `GET /orders/{id}`

```bash
curl -i "$API/orders/ORD-42"
```

```http
HTTP/2 200
content-type: application/json

{"id":"ORD-42","customerId":"CUST-7","product":"Keyboard","quantity":2}
```

## 200 OK — `GET /orders?customerId=...`

```bash
curl -i "$API/orders?customerId=CUST-42"
```

```http
HTTP/2 200

[{"id":"ORD-001","customerId":"CUST-42","product":"MacBook Air","quantity":1},
 {"id":"ORD-002","customerId":"CUST-42","product":"USB-C Hub","quantity":2}]
```

```bash
curl -i "$API/orders?customerId=CUST-100"     # 200, one order (ORD-003)
curl -i "$API/orders?customerId=CUST-NOBODY"  # 200, []  (a filter with no matches is not an error)
```

## 404 Not Found

```bash
curl -i "$API/orders/ORD-999"
```

```http
HTTP/2 404

{"error":"ORDER_NOT_FOUND","message":"Order ORD-999 does not exist"}
```

## 400 Bad Request

```bash
# business rule violated (checked in Java, both architectures)
curl -i -X POST "$API/orders" -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-42","product":"MacBook Air","quantity":0}'
```

```http
HTTP/2 400

{"error":"VALIDATION_ERROR","message":"quantity must be greater than 0"}
```

```bash
# malformed JSON
curl -i -X POST "$API/orders" -H 'Content-Type: application/json' -d '{"customerId": '
# B → {"error":"INVALID_REQUEST_BODY","message":"Request body must be a valid JSON object: ..."}
# A → {"error":"VALIDATION_ERROR","message":"Invalid request body"}   (rejected by API Gateway)

# missing query parameter
curl -i "$API/orders"
# B → {"error":"VALIDATION_ERROR","message":"customerId is required"}
# A → {"error":"VALIDATION_ERROR","message":"Missing required request parameters: [customerId]"}
```

## 405 / unknown route (Architecture B)

```bash
curl -i -X PUT "$API/orders"   # API Gateway has no PUT method → 404 ROUTE_NOT_FOUND (gateway response)
curl -i "$API/customers"       # 404 ROUTE_NOT_FOUND
```

The handler's own 405 `METHOD_NOT_ALLOWED` branch only runs when the gateway forwards a method
the handler does not support (e.g. if someone adds an `ANY` method). It is covered by unit tests.
