# Reliability and Consistency Notes

This document explains the safeguards used by the flash-sale transaction path and how the load test validates them.

## Core Risk

The critical flash-sale risk is not just high latency. The dangerous failure modes are:

- Selling more orders than available stock.
- Creating duplicate orders for the same user and offer.
- Reserving stock in Redis but losing the RabbitMQ message.
- Creating orders in MySQL while Redis and MySQL disagree.
- Retrying failed messages without idempotency.
- Processing duplicate payment webhook events more than once.

## Consistency Layers

The system uses layered safeguards instead of trusting a single mechanism.

| Layer | Responsibility |
| --- | --- |
| Redis Lua | Fast atomic admission control: sale window, stock check, stock pre-deduction, one-user-one-order reservation |
| RabbitMQ publisher confirms and returns | Detect failed or unroutable order messages after Redis reservation |
| Redis compensation script | Restore stock and remove reservation when publishing or final processing fails |
| RabbitMQ retry and DLQ | Prevent transient consumer failures from dropping messages silently |
| MySQL conditional stock update | Final durable stock deduction guard |
| MySQL unique index on `(user_id, offer_id)` | Final duplicate-order guard |
| Payment webhook event table | Idempotent handling of provider events |

## Flash-Sale Order Flow

```text
1. Client submits an authenticated flash-sale order request.
2. AuthFilter validates JWT and loads user session state from Redis.
3. Redis Lua atomically checks time window, stock, and duplicate reservation.
4. Redis decrements stock and records the user reservation.
5. Application publishes an order command to RabbitMQ.
6. If publish fails, Redis reservation compensation is executed.
7. Consumer receives the message and checks the Redis reservation.
8. Consumer creates the order in a MySQL transaction.
9. MySQL updates stock conditionally and inserts the order.
10. Duplicate-order conflicts are treated as idempotent success.
```

## Why Redis Is Not the Final Source of Truth

Redis is optimized for the hot admission path. It can quickly reject excess demand and prevent most requests from touching MySQL.

MySQL remains the durable source of truth because:

- Orders must survive Redis eviction or restart.
- Payment state depends on durable order state.
- Unique constraints provide a final correctness boundary.
- Reporting and reconciliation should use durable data.

The load test therefore validates both fast-path behavior and final database state.

## Failure Handling

### RabbitMQ Publish Failure

If Redis admission succeeds but RabbitMQ publishing fails, the system compensates Redis:

```text
restore stock
remove user reservation
record compensation metric
return failure to client
```

This prevents a user from consuming stock without an order command.

### Consumer Failure

The consumer uses manual acknowledgements. Failed messages are retried through a retry queue and eventually routed to a DLQ after the retry limit.

For dead-lettered order messages, Redis compensation is attempted so reserved stock is not trapped forever.

### Database Conflict

MySQL protects final state with:

- Conditional stock deduction: `stock = stock - 1` only when stock is greater than zero.
- Unique order constraint: one `(user_id, offer_id)` row.

These database rules remain effective even if an application-level idempotency check is bypassed.

### Duplicate Payment Webhook

Payment provider events are recorded by provider and event ID. Duplicate processed events return success without mutating the business state again.

## What the Load Test Proves

The baseline `5000 users / 100 stock` scenario verifies:

- Redis Lua admits only stock-sized demand.
- MySQL creates exactly stock-sized orders.
- MySQL unique constraints prevent duplicate user orders.
- RabbitMQ eventually drains asynchronous order work.
- Redis and MySQL agree after the run completes.
- DLQ remains empty for normal high-concurrency traffic.

This is the main portfolio claim:

```text
The system is not only fast on the happy path; it preserves business correctness
under concurrent pressure and exposes failures through metrics, queues, and
verification scripts.
```
