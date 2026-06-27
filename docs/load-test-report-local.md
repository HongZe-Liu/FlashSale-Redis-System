# Local Load Test Report

This report records reproducible local runs of the flash-sale load test.

## Environment

| Item | Value |
| --- | --- |
| Date | 2026-06-27 14:05:01 CEST |
| Machine | macOS arm64, `hzdeMacBook-Pro.local` |
| Java | OpenJDK 17.0.18 |
| Application profile | Existing local application on `localhost:8080` |
| Database | Local MySQL on `127.0.0.1:3306` |
| Redis | Local Redis on `127.0.0.1:6379` |
| RabbitMQ | Local RabbitMQ with Management API on `localhost:15672` |
| Load generator | k6 v2.0.0 |
| Load-test infrastructure mode | `LOAD_TEST_INFRA=local` |

Docker was not available on this machine, so these runs used the local-service mode supported by the load-test scripts.

## Run 1: Smoke Validation

The first run verified that the full workflow works end to end before increasing load.

| Parameter | Value |
| --- | ---: |
| Offer ID | `900001` |
| Synthetic users | `200` |
| Stock | `20` |
| k6 VUs | `50` |
| Total iterations | `200` |
| Expected accepted orders | `20` |

### k6 Summary

| Metric | Result |
| --- | ---: |
| Accepted orders | `20` |
| Stock rejections | `180` |
| Duplicate rejections | `0` |
| Other business rejections | `0` |
| HTTP failure rate | `0%` |
| HTTP p95 latency | `114.00 ms` |
| HTTP p99 latency | `133.20 ms` |
| Completed iterations | `200` |

### Final Verification

```text
orders=20 duplicateUsers=0 dbStock=0 redisStock=0 redisReservations=20 createQueue=0 retryQueue=0 dlq=0
Load-test verification passed.
```

## Run 2: Portfolio Baseline

The baseline run validates the main portfolio scenario: many users compete for limited stock, and the system must preserve correctness under concurrent pressure.

| Parameter | Value |
| --- | ---: |
| Offer ID | `900001` |
| Synthetic users | `5,000` |
| Stock | `100` |
| k6 VUs | `300` |
| Total iterations | `5,000` |
| Expected accepted orders | `100` |

### Commands

```bash
LOAD_TEST_INFRA=local USERS=5000 STOCK=100 node load-tests/scripts/prepare-load-data.mjs

k6 run \
  -e BASE_URL=http://localhost:8080 \
  -e OFFER_ID=900001 \
  -e TOKEN_FILE=load-tests/out/tokens.json \
  -e VUS=300 \
  -e ITERATIONS=5000 \
  -e MAX_DURATION=2m \
  -e P95_THRESHOLD_MS=1500 \
  -e P99_THRESHOLD_MS=3000 \
  load-tests/k6/flash-sale-order.js

LOAD_TEST_INFRA=local USERS=5000 STOCK=100 EXPECTED_ACCEPTED=100 WAIT_SECONDS=60 \
node load-tests/scripts/verify-load-result.mjs
```

### k6 Summary

| Metric | Result |
| --- | ---: |
| Accepted orders | `100` |
| Stock rejections | `4,900` |
| Duplicate rejections | `0` |
| Other business rejections | `0` |
| HTTP failure rate | `0%` |
| HTTP p50 latency | `95.64 ms` |
| HTTP p90 latency | `185.98 ms` |
| HTTP p95 latency | `266.21 ms` |
| HTTP p99 latency | `361.36 ms` |
| HTTP max latency | `422.72 ms` |
| Completed iterations | `5,000` |
| Iteration rate | `2,609 req/s` |

The raw k6 summary is written to:

```text
load-tests/out/k6-summary.json
```

### Final Consistency Verification

| Check | Expected | Actual | Status |
| --- | ---: | ---: | --- |
| MySQL order count | `100` | `100` | PASS |
| Duplicate users | `0` | `0` | PASS |
| MySQL remaining stock | `0` | `0` | PASS |
| Redis remaining stock | `0` | `0` | PASS |
| Redis reservation count | `100` | `100` | PASS |
| RabbitMQ create queue depth | `0` | `0` | PASS |
| RabbitMQ retry queue depth | `0` | `0` | PASS |
| RabbitMQ DLQ depth | `0` | `0` | PASS |

Verification output:

```text
orders=100 duplicateUsers=0 dbStock=0 redisStock=0 redisReservations=100 createQueue=0 retryQueue=0 dlq=0
Load-test verification passed.
```

## Observations

- Redis Lua admitted exactly the configured stock quantity.
- All excess requests were rejected as stock failures rather than unexpected business errors.
- RabbitMQ drained the asynchronous order flow before verification completed.
- Redis stock, Redis reservation count, MySQL stock, and MySQL order count ended in a consistent state.
- No messages were left in the create queue, retry queue, or dead-letter queue.
- The local baseline completed quickly on a development machine, so these latency numbers should be interpreted as local evidence rather than production capacity claims.

## Conclusion

The local portfolio baseline admitted exactly 100 orders out of 5,000 concurrent attempts and rejected the remaining demand without overselling or creating duplicate user orders. RabbitMQ drained successfully and no messages were routed to the dead-letter queue. Redis and MySQL ended in a consistent state.

This run supports the main engineering claim of the project:

```text
Under concurrent flash-sale traffic, the system preserves stock correctness,
prevents duplicate orders, drains asynchronous work, and exposes enough
verification points to prove final consistency.
```
