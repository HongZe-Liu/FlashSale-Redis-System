# Payment Acceptance Test

## 1. 目标

这份文档用于轻量验收 Demo 版支付闭环。

当前项目不接入真实 Stripe，而是使用 `MockPaymentProvider` 模拟第三方支付。验收重点不是“能不能调通 Stripe API”，而是确认后端支付模型是否完整：

- 秒杀下单后异步创建待支付订单。
- 订单保存支付金额和币种快照。
- 后端创建独立的 `payment_order` 支付单。
- Mock webhook 作为可信支付结果来源。
- webhook 重复通知不会重复改业务状态。
- 超时未支付订单会关闭并回补库存。
- 异常 webhook 不会错误地把订单改为已支付。

## 2. 前置环境

需要本机启动：

```text
MySQL
Redis
RabbitMQ
Spring Boot application
```

建议使用 Java 11 运行项目。MyBatis-Plus 3.4.3 在 Java 17 下可能触发 lambda 反射访问问题，导致登录接口异常。

示例启动命令：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home \
JWT_SECRET=dev-only-change-me-dev-only-change-me-32bytes \
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

如果本地库名不是 `FlashSalePaymentApplication`，可以通过环境变量或启动参数覆盖数据源。

示例：

```bash
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/flash_sale_payment?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true" \
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home \
JWT_SECRET=dev-only-change-me-dev-only-change-me-32bytes \
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

本地测试可以临时调短超时扫描间隔：

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local --app.payment.timeout-scan-fixed-delay-ms=2000"
```

## 3. 测试数据约定

本文档使用以下占位符：

```text
<db>                 MySQL schema name, for example flash_sale_payment
<admin-token>        admin access token
<user-token>         customer access token
<offer-id>           flash-sale offer id
<order-id>           order id returned by order API
<provider-payment-id> providerPaymentId returned by payment API
```

本地种子用户通常包含：

```text
admin@flashsale.dev
alice@example.com
```

如果需要跳过邮件发送，可以直接在 Redis 写入验证码：

```bash
redis-cli setex login:code:admin@flashsale.dev 120 123456
redis-cli setex login:code:alice@example.com 120 123456
```

注意：当前邮箱正则不接受本地部分带点号的邮箱，例如 `codex.pay@example.com` 可能会失败。测试新用户时建议使用 `codexpay@example.com` 这类简单格式。

## 4. 登录

管理员登录：

```bash
curl -X POST http://localhost:8080/user/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"admin@flashsale.dev","code":"123456"}'
```

普通用户登录：

```bash
curl -X POST http://localhost:8080/user/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"alice@example.com","code":"123456"}'
```

响应中的 `data` 就是 access token。

后续请求使用：

```text
Authorization: Bearer <token>
```

## 5. 发布秒杀活动

如果已有可用活动，可以直接发布：

```bash
curl -X POST http://localhost:8080/flash-sales/<offer-id>/publish \
  -H "Authorization: Bearer <admin-token>"
```

预期响应：

```json
{"success":true,"errorMsg":null,"data":null,"total":null}
```

检查 Redis 预热结果：

```bash
redis-cli get flashsale:stock:<offer-id>
redis-cli hgetall flashsale:offer:<offer-id>
redis-cli smembers flashsale:order:<offer-id>
```

## 6. 下单并等待异步落库

用户提交秒杀订单：

```bash
curl -X POST http://localhost:8080/flash-sales/<offer-id>/orders \
  -H "Authorization: Bearer <user-token>"
```

预期响应：

```json
{"success":true,"errorMsg":null,"data":<order-id>,"total":null}
```

注意：接口返回成功只代表 Redis 预扣成功并且订单消息已发送到 RabbitMQ。订单真正落库由 RabbitMQ consumer 异步完成，所以需要轮询数据库。

检查订单落库：

```sql
SELECT id, user_id, offer_id, status, pay_amount, currency, expire_time
FROM orders
WHERE id = <order-id>;
```

预期结果：

```text
status      = 1
pay_amount  = offers.price_amount at order creation time
currency    = EUR
expire_time is not null
```

状态 `1` 表示 `PENDING_PAYMENT`。

## 7. 创建 Mock 支付单

```bash
curl -X POST http://localhost:8080/payments/orders/<order-id> \
  -H "Authorization: Bearer <user-token>" \
  -H "Content-Type: application/json" \
  -d '{"provider":"MOCK"}'
```

预期响应：

```json
{
  "success": true,
  "errorMsg": null,
  "data": {
    "paymentOrderId": 123,
    "orderId": 456,
    "provider": "MOCK",
    "providerPaymentId": "mock_pay_456",
    "amount": 475,
    "currency": "EUR",
    "status": "PENDING",
    "checkoutUrl": "mock://checkout/..."
  },
  "total": null
}
```

检查支付单：

```sql
SELECT id, order_id, provider, provider_payment_id, amount, currency, status, expires_at
FROM payment_order
WHERE order_id = <order-id>;
```

预期结果：

```text
provider = MOCK
amount   = orders.pay_amount
currency = orders.currency
status   = PENDING
```

## 8. 模拟支付成功 Webhook

Mock webhook 的成功事件类型固定为：

```text
payment.succeeded
```

发送 webhook：

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

预期响应：

```json
{"success":true,"errorMsg":null,"data":null,"total":null}
```

检查支付完成状态：

```sql
SELECT status, pay_time
FROM orders
WHERE id = <order-id>;

SELECT status, paid_at
FROM payment_order
WHERE order_id = <order-id>;

SELECT provider, event_id, event_type, order_id, status
FROM payment_webhook_event
WHERE event_id = 'mock_evt_001';
```

预期结果：

```text
orders.status = 2
orders.pay_time is not null
payment_order.status = PAID
payment_order.paid_at is not null
payment_webhook_event.status = PROCESSED
```

状态 `2` 表示 `PAID`。

## 9. 验证 Webhook 幂等

重复发送第 8 节完全相同的 webhook。

预期结果：

```text
HTTP response success = true
orders.status remains 2
payment_order.status remains PAID
payment_webhook_event still has one row for provider + event_id
```

这里的成功幂等只针对已经 `PROCESSED` 的同一事件。也就是说，同一个成功 webhook 重复到达时，系统可以直接按成功返回，但不会重复更新订单或支付单。

检查事件数量：

```sql
SELECT COUNT(*)
FROM payment_webhook_event
WHERE provider = 'MOCK'
  AND event_id = 'mock_evt_001';
```

预期结果：

```text
1
```

## 10. 查询支付状态

```bash
curl http://localhost:8080/payments/orders/<order-id> \
  -H "Authorization: Bearer <user-token>"
```

预期响应：

```json
{
  "success": true,
  "data": {
    "orderId": 456,
    "orderStatus": "PAID",
    "paymentStatus": "PAID",
    "provider": "MOCK",
    "amount": 475,
    "currency": "EUR"
  }
}
```

## 11. 验证超时关单和库存回补

再使用另一个用户或另一个 offer 创建一笔新订单，让订单保持 `PENDING_PAYMENT`。

记录下单前库存：

```sql
SELECT stock FROM flash_sale_offers WHERE offer_id = <offer-id>;
```

```bash
redis-cli get flashsale:stock:<offer-id>
```

下单后，预期 MySQL 和 Redis 库存都减 1。

为了快速验收，可以手动把订单改成已过期：

```sql
UPDATE orders
SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE)
WHERE id = <order-id>;
```

等待定时任务扫描后，检查订单：

```sql
SELECT status
FROM orders
WHERE id = <order-id>;
```

预期结果：

```text
4
```

状态 `4` 表示 `EXPIRED`。

检查库存回补：

```sql
SELECT stock FROM flash_sale_offers WHERE offer_id = <offer-id>;
```

```bash
redis-cli get flashsale:stock:<offer-id>
```

预期结果：

```text
MySQL stock returns to the value before this order
Redis stock returns to the value before this order
```

第一版设计中，订单过期后仍然保留 Redis 的一人一单资格，所以同一用户再次抢同一个 offer 预期失败：

```text
不能重复下单
```

## 12. 建议补充的异常验收

### 金额不匹配

发送 webhook 时把 `amount` 改成错误金额。

预期：

```text
response success = false
orders.status remains PENDING_PAYMENT
payment_order.status remains PENDING
payment_webhook_event.status = FAILED
```

### 币种不匹配

发送 webhook 时把 `currency` 改成错误币种，例如 `USD`。

预期：

```text
response success = false
orders.status remains PENDING_PAYMENT
payment_order.status remains PENDING
payment_webhook_event.status = FAILED
```

### 订单号不匹配

发送 webhook 时使用正确的 `providerPaymentId`，但传入错误的 `orderId`。

预期：

```text
response success = false
orders.status remains PENDING_PAYMENT
payment_order.status remains PENDING
payment_webhook_event.status = FAILED
```

### 已过期订单收到支付成功

先让订单变为 `EXPIRED`，再发送支付成功 webhook。

预期：

```text
response success = false
orders.status remains EXPIRED
payment_order.status does not become PAID
payment_webhook_event.status = FAILED
```

### 失败 Webhook 重复到达

对上一条失败 webhook 使用相同的 `eventId` 再发送一次。

预期：

```text
response success = false
payment_webhook_event still has one row for provider + event_id
the duplicate failed event must not be treated as success
```

### 已支付订单再次创建支付单

对已经 `PAID` 的订单再次调用：

```bash
curl -X POST http://localhost:8080/payments/orders/<order-id> \
  -H "Authorization: Bearer <user-token>" \
  -H "Content-Type: application/json" \
  -d '{"provider":"MOCK"}'
```

预期：

```text
response success = false
errorMsg = 订单状态不允许支付
```

## 13. Demo 完成标准

支付部分作为 Demo，可以认为达到阶段三轻量完成标准时，应满足：

- 成功支付闭环可以手工跑通。
- 已成功 webhook 重复通知不会重复更新业务状态。
- 已失败 webhook 重复通知不会被伪装成成功。
- 金额、币种、订单号不匹配的 webhook 会被拒绝。
- 超时订单会自动变为 `EXPIRED`。
- 超时关单会回补 MySQL 和 Redis 库存。
- 文档明确说明当前使用 Mock Provider，不声称已经接入真实 Stripe。
- 代码中保留 `PaymentProvider` 抽象，后续可以替换为 Stripe Provider。

推荐展示表述：

```text
Implemented a Stripe-ready payment workflow architecture with a Mock Payment Provider,
covering payment order creation, webhook-driven settlement, idempotent event handling,
amount validation, timeout cancellation, and stock compensation.
```

## 14. Stripe 扩展说明

当前 Demo 不需要真实接入 Stripe。

如果后续要接 Stripe，主要新增内容是：

- 新增 `StripePaymentProvider` 实现 `PaymentProvider`。
- 创建 Stripe Checkout Session 或 PaymentIntent。
- 把 Stripe 的 session id 或 payment intent id 写入 `provider_payment_id`。
- 新增 Stripe webhook endpoint。
- 校验 Stripe webhook signature。
- 把 Stripe 的成功事件转换成当前统一的支付成功处理流程。

业务核心流程不应该大改：

```text
orders
payment_order
payment_webhook_event
PaymentProvider abstraction
webhook idempotency
order/payment status transition
```

也就是说，Mock Provider 是 Demo 阶段的第三方支付替身，Stripe Provider 是后续生产接入的渠道替换。
