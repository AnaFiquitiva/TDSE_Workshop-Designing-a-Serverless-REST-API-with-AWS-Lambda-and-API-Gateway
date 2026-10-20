# Final challenge — prediction for `DELETE /orders/{id}`

Written and committed **before** any DELETE code exists. The point is to compare it afterwards
with what the change actually required (see the "Outcome" section, added in a later commit).

Expected behavior: `DELETE /orders/ORD-42` → `204 No Content` (empty body);
`DELETE /orders/ORD-999` → `404 Not Found` with the standard error body.

> ✍️ This prediction was drafted together with the AI agent. If my own prediction differed,
> I note the differences here: *(…)*

| Question | Prediction |
|---|---|
| **Which Java classes should change?** | `OrderRepository` gets a `delete(id)` returning whether something was removed, implemented in `InMemoryOrderRepository`. `OrderService` gets `deleteOrder(id)`, which throws `OrderNotFoundException` if nothing was removed. `OrdersApiHandler` gets a `DELETE` case on `/orders/{id}` returning 204, and its `Allow` header becomes `GET, DELETE`. **Not** `Order`, `CreateOrderRequest` or the exceptions, since 404 already exists as `OrderNotFoundException`. |
| **Do we need a new Lambda function?** | **No** in Architecture B (the final API). The same `orders-b-proxy` function handles the new route. In Architecture A it *would* need a new function (`orders-a-delete-order`) plus a new `DirectOrderHandlers` method, which is one of the scaling costs of A. A is not extended. |
| **Does API Gateway configuration change?** | **Yes, a little.** API Gateway rejects methods it doesn't know about before calling Lambda, so a `DELETE` method has to be added on `/orders/{id}` with proxy integration (one new `Events` entry in `template.yaml`). Nothing else: no templates or response mappings. |
| **Where should the deletion logic reside?** | In `OrderService.deleteOrder` (the application logic), with the actual removal in the repository. The adapter only extracts `id` and calls the service. |
| **Where should the 204/404 decision reside?** | Split. The **service** decides the *fact* that the order does not exist (it throws `OrderNotFoundException`). The **HTTP adapter** decides what that fact means in HTTP: success → 204 with no body, `OrderNotFoundException` → 404 (the existing mapping). The service must not know the numbers 204/404. |
| **Which tests should be added?** | `OrderServiceTest`: deleting an existing order removes it (a later `getOrder` throws), deleting a missing order throws `OrderNotFoundException`, a blank id throws `ValidationException`, and deleting one order leaves the customer's other orders. `OrdersApiHandlerTest`: `DELETE` existing → 204 with empty body; a `GET` afterwards → 404; `DELETE` missing → 404 `ORDER_NOT_FOUND`; deleting twice → second one 404; `Allow` header now lists `DELETE`. |

Open question to check: should a second DELETE of the same order return 404 or 204? DELETE is
*idempotent* in terms of server **state** (the order is gone either way), not in terms of the
response code. The workshop's contract says nonexistent → 404, so the second call returns 404.
