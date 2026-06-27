# Performance Testing Strategy

This document describes the reproducible load-testing workflow used to validate the flash-sale order path under concurrent traffic.

The goal is not only to measure request throughput. For a flash-sale system, the primary engineering concern is whether the system remains correct when many users compete for limited stock.

## Scope

The main scenario targets the hot path:

```text
POST /flash-sales/{offerId}/orders
  -> JWT authentication and Redis-backed session lookup
  -> Redis Lua admission control
  -> RabbitMQ order command publish
  -> RabbitMQ consumer
  -> MySQL order creation and final stock deduction
```

Authentication endpoints, offer-management endpoints, and payment webhooks can be tested separately. They are intentionally excluded from the baseline flash-sale pressure test so that the result focuses on the highest-concurrency transaction path.

## Test Objectives

The baseline test validates these properties:

- No overselling: final order count must never exceed configured flash-sale stock.
- No duplicate orders: one user can create at most one order for the same offer.
- Final consistency: Redis stock, Redis reservation set, MySQL stock, and MySQL orders must agree after RabbitMQ drains.
- Queue health: RabbitMQ create and retry queues should drain; the dead-letter queue should stay empty for the normal scenario.
- Latency visibility: p95 and p99 HTTP latency should be recorded and compared between runs.

## Tooling

The project uses a lightweight test-as-code approach:

| Area | Tool | Purpose |
| --- | --- | --- |
| Load generation | k6 | Simulates concurrent users placing orders through the real HTTP API |
| Test data setup | Node.js script | Creates a dedicated test offer, synthetic users, JWTs, and Redis login state |
| Final verification | Node.js script | Checks MySQL, Redis, and RabbitMQ after the load test |
| Observability | Actuator, Micrometer, Prometheus | Exposes business and infrastructure metrics |

The load-test assets live in:

```text
load-tests/
  k6/flash-sale-order.js
  scripts/prepare-load-data.mjs
  scripts/verify-load-result.mjs
  sql/verify-result.sql
```

## Baseline Scenario

Recommended first portfolio scenario:

| Parameter | Value |
| --- | ---: |
| Synthetic users | 5,000 |
| Flash-sale stock | 100 |
| Virtual users | 300 |
| Total order attempts | 5,000 |
| Expected successful orders | 100 |

This intentionally creates more demand than stock. The expected result is that only 100 users are admitted, and all remaining valid requests are rejected as out of stock.

## Execution Flow

Run the fast Java test suite first:

```bash
./mvnw test
```

Start the local Docker Compose stack:

```bash
docker compose up -d --build
```

If Docker is not available, run MySQL, Redis, and RabbitMQ locally, then start the application with the local profile:

```bash
JWT_SECRET=dev-only-change-me-dev-only-change-me-32bytes \
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

Prepare synthetic users and the dedicated test offer:

```bash
USERS=5000 STOCK=100 node load-tests/scripts/prepare-load-data.mjs
```

For local services without Docker, set:

```bash
LOAD_TEST_INFRA=local USERS=5000 STOCK=100 node load-tests/scripts/prepare-load-data.mjs
```

Run the k6 load test:

```bash
k6 run \
  -e BASE_URL=http://localhost:8080 \
  -e OFFER_ID=900001 \
  -e TOKEN_FILE=load-tests/out/tokens.json \
  -e VUS=300 \
  -e ITERATIONS=5000 \
  -e MAX_DURATION=2m \
  -e P95_THRESHOLD_MS=1000 \
  -e P99_THRESHOLD_MS=3000 \
  load-tests/k6/flash-sale-order.js
```

Verify the final state:

```bash
USERS=5000 STOCK=100 EXPECTED_ACCEPTED=100 node load-tests/scripts/verify-load-result.mjs
```

For local RabbitMQ Management credentials, override:

```bash
LOAD_TEST_INFRA=local \
RABBITMQ_MANAGEMENT_USERNAME=guest \
RABBITMQ_MANAGEMENT_PASSWORD=guest \
USERS=5000 STOCK=100 EXPECTED_ACCEPTED=100 \
node load-tests/scripts/verify-load-result.mjs
```

## Acceptance Criteria

For the baseline scenario, the run passes only if all of these are true:

| Check | Expected |
| --- | --- |
| MySQL order count for test offer | `100` |
| Duplicate users in `orders` | `0` |
| MySQL remaining stock | `0` |
| Redis remaining stock | `0` |
| Redis reservation set size | `100` |
| RabbitMQ create queue depth | `0` after drain |
| RabbitMQ retry queue depth | `0` after drain |
| RabbitMQ dead-letter queue depth | `0` |
| k6 unexpected business rejections | `0` |

## Metrics to Watch

Application metrics are exported through:

```text
GET /actuator/prometheus
```

Useful business metrics include:

- `flashsale_order_request_total`
- `flashsale_order_request_success_total`
- `flashsale_order_request_failure_total`
- `flashsale_mq_publish_success_total`
- `flashsale_mq_publish_failure_total`
- `flashsale_mq_consume_success_total`
- `flashsale_mq_consume_failure_total`
- `flashsale_mq_dead_letter_total`
- `flashsale_order_create_success_total`
- `flashsale_order_create_failure_total`
- `flashsale_compensation_redis_success_total`

Infrastructure signals to inspect during larger runs:

- JVM CPU and heap usage
- HTTP request p95 and p99 latency
- Redis command latency and connection pool pressure
- RabbitMQ queue depth, publish rate, consume rate, and unacknowledged messages
- MySQL connection pool usage, slow queries, row locks, and transaction latency

## Production Safety Notes

Do not run this load test against a real production dataset without additional controls.

Production-grade load testing should use:

- Dedicated synthetic users.
- Dedicated test offers and merchants.
- An isolated payment provider or mock provider.
- A clear load-test marker in logs and metrics.
- Rate limits and an emergency stop switch.
- Alerting and an operator watching dashboards during the run.
- A cleanup plan for synthetic data.

For this portfolio project, the recommended demonstration path is local Docker first, then a production-like staging environment if available.

## How This Supports the Portfolio Story

This workflow demonstrates that the system has been validated at three levels:

- Java tests verify normal business behavior and integration points.
- k6 tests exercise the real HTTP API under concurrent traffic.
- Post-test verification proves that MySQL, Redis, and RabbitMQ end in a consistent state.

The strongest claim is not "the service can receive many requests." The stronger engineering claim is:

```text
Under concurrent flash-sale traffic, the system preserves stock correctness,
prevents duplicate orders, drains asynchronous work, and exposes enough metrics
to diagnose bottlenecks.
```
