# Observability Acceptance Test

This checklist validates the Phase 4 observability baseline.

## Preconditions

- MySQL, Redis, and RabbitMQ are running.
- The application is running with the `local` profile.
- The local profile exposes:

```text
/actuator/health
/actuator/info
/actuator/metrics
/actuator/prometheus
```

Default or production-like configuration only exposes `health` and `prometheus`.

## Actuator

Health should be accessible without authentication in the local profile:

```bash
curl http://localhost:8080/actuator/health
```

Expected result when dependencies are healthy:

```text
"status":"UP"
```

If MySQL, Redis, or RabbitMQ is down, health may return `DOWN`. Use the health details to identify the failing dependency.

Prometheus metrics should be available:

```bash
curl http://localhost:8080/actuator/prometheus
```

Expected technical metrics include JVM, HTTP server, Tomcat, and HikariCP metrics.

Micrometer metric names use dots in code, but Prometheus output uses underscores and type suffixes. For example:

```text
flashsale.payment.webhook.success
flashsale_payment_webhook_success_total
```

## Business Metrics

Run the existing local acceptance flow from the README or `docs/payment-acceptance-test.md`.

After sending a verification code and logging in, check:

```bash
curl http://localhost:8080/actuator/prometheus | grep 'flashsale_auth'
```

Expected examples:

```text
flashsale_auth_code_sent_total
flashsale_auth_login_success_total
```

After submitting a flash-sale order, check:

```bash
curl http://localhost:8080/actuator/prometheus | grep 'flashsale_order_request'
curl http://localhost:8080/actuator/prometheus | grep 'flashsale_mq'
curl http://localhost:8080/actuator/prometheus | grep 'flashsale_order_create'
```

Expected examples:

```text
flashsale_order_request_total
flashsale_order_request_success_total
flashsale_mq_publish_success_total{destination="order_create",...}
flashsale_mq_consume_success_total
flashsale_order_create_success_total
```

If a duplicate order path is exercised, it should increment:

```text
flashsale_order_create_idempotent_total
```

After creating a payment, check:

```bash
curl http://localhost:8080/actuator/prometheus | grep 'flashsale_payment_create'
```

Expected examples:

```text
flashsale_payment_create_success_total{provider="mock",...}
flashsale_payment_create_reused_total{provider="mock",...}
flashsale_payment_create_failure_total{provider="mock",reason="provider_exception",...}
```

After sending a mock payment webhook, check:

```bash
curl http://localhost:8080/actuator/prometheus | grep 'flashsale_payment_webhook'
```

Expected examples:

```text
flashsale_payment_webhook_received_total{provider="mock",...}
flashsale_payment_webhook_success_total{provider="mock",...}
```

Repeat the same webhook payload. It should increment duplicate only, not ordinary success:

```text
flashsale_payment_webhook_duplicate_total{provider="mock",status="processed",...}
```

If Redis reservation compensation is triggered, check:

```bash
curl http://localhost:8080/actuator/prometheus | grep 'flashsale_compensation_redis'
```

Expected result categories:

```text
flashsale_compensation_redis_success_total
flashsale_compensation_redis_failure_total
flashsale_compensation_redis_noop_total
```

## Prometheus

Use `infra/prometheus/prometheus.yml` for a local Prometheus container.

When Prometheus runs in Docker on macOS, keep:

```text
host.docker.internal:8080
```

When Prometheus runs directly on the host, change the target to:

```text
localhost:8080
```

Prometheus should show the `flash-sale-platform` target as `UP`.
