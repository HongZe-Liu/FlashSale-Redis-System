# Flash Sale Platform Backend

High-concurrency flash sale platform backend built with Spring Boot, Redis, RabbitMQ, and MySQL.

This project is being refactored from a general coupon and shop demo into a focused backend portfolio project. The target scope is a realistic transaction system: authentication, flash-sale admission control, asynchronous order creation, payment workflow, idempotency, compensation, and observability.

## Project Goal

The project is designed to demonstrate backend engineering depth around a complete transaction flow:

```text
Login
  -> Flash sale request
  -> Redis Lua admission control
  -> Stock pre-deduction
  -> Asynchronous order creation
  -> Payment initiation
  -> Payment webhook
  -> Order completion or compensation
```

The current implementation keeps Redis for cache and atomic flash-sale checks. RabbitMQ has replaced Redis Stream as the asynchronous order pipeline for flash-sale order creation, which better reflects common production architecture.

## Core Capabilities

- Email verification login with JWT access token and refresh token support
- Redis-based verification code throttling and login retry protection
- Redis Lua script for atomic flash-sale stock check and one-user-one-order control
- Admin flash-sale publish flow that preheats Redis from MySQL before purchase traffic starts
- RabbitMQ-based asynchronous order creation with publisher confirm, manual ack, retry queue, and DLQ
- Idempotent Redis reservation compensation when order message publishing or final consumption fails
- MySQL transaction boundary for final order persistence
- Pending-payment order state with amount/currency snapshots
- Mock payment provider for local payment workflow validation
- Payment webhook idempotency with provider event records
- Timeout cancellation for unpaid orders with stock compensation
- Redisson and Redis utilities for distributed coordination
- Spring Security based authentication filter
- Environment-driven configuration for MySQL, Redis, RabbitMQ, and JWT secret
- Maven Wrapper for reproducible local builds

## Current Scope

The project is intentionally being narrowed to the transaction path:

- Authentication
- Merchant and offer catalog
- Flash-sale offer setup
- Flash-sale order request
- Future payment workflow
- Future monitoring and operational visibility

The old blog, follow, comment, and upload features are no longer part of the target transaction path because they do not strengthen the resume story.

## Planned Architecture

```mermaid
flowchart LR
    User["User"] --> Auth["Auth API"]
    User --> FlashSale["Flash Sale API"]
    FlashSale --> Redis["Redis Lua\nstock + duplicate check"]
    FlashSale --> MQ["RabbitMQ\norder message"]
    MQ --> Worker["Order Consumer"]
    Worker --> MySQL["MySQL\norders + stock"]
    User --> Payment["Payment API"]
    Payment --> Provider["Stripe / Bancontact"]
    Provider --> Webhook["Payment Webhook"]
    Webhook --> MySQL
    MySQL --> Metrics["Micrometer / Prometheus"]
```

## Tech Stack

- Java 11
- Spring Boot 2.3.x
- Spring Security
- MyBatis-Plus
- MySQL
- Redis
- Redisson
- RabbitMQ
- JWT
- Maven Wrapper

## Main API Areas

| Area | Endpoint | Purpose |
| --- | --- | --- |
| Auth | `POST /user/code` | Send email verification code |
| Auth | `POST /user/login` | Login and issue tokens |
| Auth | `POST /user/refresh` | Rotate refresh token |
| Auth | `POST /user/logout` | Logout |
| Auth | `GET /user/me` | Current user profile |
| Merchant | `GET /merchants/{id}` | Query merchant by id |
| Merchant | `POST /merchants` | Create merchant, admin only |
| Merchant | `PUT /merchants` | Update merchant, admin only |
| Offer | `POST /offers` | Create offer, admin only |
| Offer | `GET /offers/merchant/{merchantId}` | Query offers by merchant |
| Flash Sale | `POST /flash-sales` | Create flash-sale offer, admin only |
| Flash Sale | `POST /flash-sales/{offerId}/publish` | Publish flash-sale offer and preheat Redis, admin only |
| Flash Sale | `POST /flash-sales/{offerId}/orders` | Submit flash-sale order request |
| Payment | `POST /payments/orders/{orderId}` | Create a payment order for a pending order |
| Payment | `GET /payments/orders/{orderId}` | Query order and payment status |
| Payment Webhook | `POST /payments/webhooks/mock` | Local/test mock payment success webhook |

## Local Build

The local profile uses Java 11 and the MySQL schema `flash_sale_platform`.

Use the Maven Wrapper included in the repository:

```bash
./mvnw clean test
```

Run the application with the local profile:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home \
JWT_SECRET=dev-only-change-me-dev-only-change-me-32bytes \
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

## Docker Demo

Start the full local demo stack with Docker Compose:

```bash
docker compose up -d --build
```

This starts the Spring Boot app, MySQL, Redis, RabbitMQ, and Prometheus. MySQL initializes the `flash_sale_platform` schema from `src/main/resources/db/flash_sale_platform.sql` the first time its Docker volume is created.

Useful local URLs:

```text
API: http://localhost:8080
Health: http://localhost:8080/actuator/health
RabbitMQ: http://localhost:15672
Prometheus: http://localhost:9090
```

Default RabbitMQ credentials are `flash_sale` / `flash_sale`.

To override ports or local demo credentials, copy `.env.example` to `.env` and edit it before starting Compose:

```bash
cp .env.example .env
docker compose up -d --build
```

Reset all local container data, including the MySQL seed data:

```bash
docker compose down -v
docker compose up -d --build
```

Important configuration can be supplied through environment variables:

```text
MYSQL_URL
MYSQL_USERNAME
MYSQL_PASSWORD
REDIS_HOST
REDIS_PORT
REDIS_PASSWORD
RABBITMQ_HOST
RABBITMQ_PORT
RABBITMQ_USERNAME
RABBITMQ_PASSWORD
JWT_SECRET
PAYMENT_PROVIDER
PAYMENT_ORDER_EXPIRE_MINUTES
PAYMENT_TIMEOUT_SCAN_FIXED_DELAY_MS
PAYMENT_TIMEOUT_SCAN_BATCH_SIZE
MOCK_WEBHOOK_SECRET
```

## Local Acceptance Flow

For the lightweight manual payment acceptance checklist, see
[`docs/payment-acceptance-test.md`](docs/payment-acceptance-test.md).

1. Import `src/main/resources/db/flash_sale_platform.sql` into the `flash_sale_platform` schema.
2. Start MySQL, Redis, and RabbitMQ locally.
3. Start the application with the `local` profile.
4. Login as the seeded admin user `admin@flashsale.dev`.
5. Publish a flash-sale offer:

```bash
curl -X POST http://localhost:8080/flash-sales/1/publish \
  -H "Authorization: Bearer <admin-access-token>"
```

Publishing writes the Redis keys required by the Lua purchase path:

```text
flashsale:stock:{offerId}
flashsale:offer:{offerId}
flashsale:order:{offerId}
```

6. Login as a seeded customer such as `alice@example.com`.
7. Submit a flash-sale order:

```bash
curl -X POST http://localhost:8080/flash-sales/1/orders \
  -H "Authorization: Bearer <user-access-token>"
```

Expected checks after a successful order:

```text
GET flashsale:stock:1              -> stock decreases by 1
SISMEMBER flashsale:order:1 2      -> 1
SELECT * FROM orders WHERE user_id = 2 AND offer_id = 1;
RabbitMQ flashsale.order.create.queue -> message is consumed and acked
orders.status                        -> 1 (PENDING_PAYMENT)
orders.pay_amount                    -> copied from offers.price_amount
orders.currency                      -> EUR
orders.expire_time                   -> not null
```

The RabbitMQ flash-sale order pipeline uses:

```text
flashsale.order.exchange -> flashsale.order.create.queue
flashsale.order.retry.exchange -> flashsale.order.create.retry.queue
flashsale.order.dead.exchange -> flashsale.order.create.dlq
```

If RabbitMQ publishing fails after Redis Lua has reserved stock and user qualification, the application runs an idempotent Redis compensation script. If consumption fails, the message is routed to the retry queue and eventually to the DLQ after the retry limit. The main queue also has a DLX so framework-level poison messages, such as invalid JSON, are dead-lettered instead of being silently dropped.

8. Create a mock payment for the pending order:

```bash
curl -X POST http://localhost:8080/payments/orders/<order-id> \
  -H "Authorization: Bearer <user-access-token>" \
  -H "Content-Type: application/json" \
  -d '{"provider":"MOCK"}'
```

The response includes a mock `providerPaymentId`, amount, currency, status, and checkout URL.

9. Simulate the provider payment success webhook:

```bash
curl -X POST http://localhost:8080/payments/webhooks/mock \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "mock_evt_001",
    "eventType": "payment.succeeded",
    "providerPaymentId": "<provider-payment-id>",
    "orderId": <order-id>,
    "amount": 475,
    "currency": "EUR"
  }'
```

Expected checks after a successful mock webhook:

```text
SELECT status, pay_time FROM orders WHERE id = <order-id>;       -> status = 2 (PAID)
SELECT status, paid_at FROM payment_order WHERE order_id = <id>; -> status = PAID
SELECT status FROM payment_webhook_event WHERE event_id='mock_evt_001'; -> PROCESSED
```

Repeat the same webhook payload. It should return success without changing the business state again.

Mock webhooks are intended for `local` and `test` profiles. In other environments, set and send `MOCK_WEBHOOK_SECRET` / `X-Mock-Webhook-Secret`.

If a `PENDING_PAYMENT` order passes `expire_time`, the scheduled timeout task marks it as `EXPIRED`, marks any pending payment order as `EXPIRED`, restores MySQL stock, and restores Redis stock when the Redis stock key still exists. The first implementation keeps the one-user-one-order qualification, so the same user still cannot re-buy the same offer after expiration.

## Observability

The application exposes Spring Boot Actuator and Prometheus metrics.

Local profile endpoints:

```text
GET /actuator/health
GET /actuator/info
GET /actuator/metrics
GET /actuator/prometheus
```

Default or production-like configuration only exposes `health` and `prometheus`; production access should still be restricted by network policy, gateway rules, or Spring Security configuration.

Use the local Prometheus scrape config at:

```text
infra/prometheus/prometheus.yml
```

For the manual observability checklist, see
[`docs/observability-acceptance-test.md`](docs/observability-acceptance-test.md).

## Refactor Roadmap

1. Project boundary cleanup
   - Remove non-core social modules
   - Externalize sensitive configuration
   - Clarify project positioning and documentation
   - Add explicit flash-sale publish/preheat flow

2. RabbitMQ order pipeline
   - Replace Redis Stream with RabbitMQ
   - Add publisher confirm, manual ack, retry queue, and dead-letter queue
   - Add compensation for message publishing or consumption failures
   - Status: implemented as the phase-two A+ design

3. Payment module
   - Add payment order model and order state machine
   - Add provider abstraction
   - Add Mock provider payment flow
   - Handle webhook idempotency and timeout cancellation
   - Next: integrate Stripe for EUR/Bancontact-oriented payment flow

4. Observability
   - Add Spring Boot Actuator and Micrometer
   - Export Prometheus metrics
   - See [`docs/phase-4-observability-plan.md`](docs/phase-4-observability-plan.md)
   - Build Grafana dashboards for login, flash sale, MQ, order, and payment paths

5. Production readiness
   - Add focused tests for login, flash sale, MQ, and payment idempotency
   - Add Docker Compose for local infrastructure
   - Add load testing and failure scenario documentation

## Resume Positioning

> A high-concurrency flash sale and payment backend built with Spring Boot, Redis, RabbitMQ, and MySQL. The system focuses on atomic stock deduction, asynchronous order processing, idempotency, payment workflow design, compensation, and observability.
