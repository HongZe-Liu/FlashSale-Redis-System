# Phase 3 Payment Closure Plan

## 1. 阶段三定位

阶段三目标是把当前“秒杀后异步创建订单”的链路，升级成完整的订单与支付闭环。

第一版不直接接入 Stripe，而是先实现 Mock 支付闭环：

```text
秒杀成功
  -> RabbitMQ Consumer 创建 PENDING_PAYMENT 订单
  -> 用户发起支付
  -> 后端创建 payment_order 支付单
  -> MockPaymentProvider 返回模拟支付信息
  -> Mock webhook 模拟第三方支付成功通知
  -> 后端幂等处理 webhook
  -> 事务更新 payment_order 和 orders 为 PAID
  -> 超时未支付订单自动 EXPIRED
  -> 回补 MySQL 库存和 Redis 库存
```

这样做的目的不是绕开 Stripe，而是先把后端支付系统最核心的能力练稳：

- 订单状态机
- 支付单
- 第三方 provider 抽象
- webhook 异步通知
- webhook 幂等
- 支付成功事务更新
- 超时取消
- 库存补偿

Stripe / Bancontact / EUR 接入放在 Mock 闭环稳定之后进行。

## 2. 第一版范围

### 本阶段第一版要做

- 新增订单状态枚举 `OrderStatus`。
- RabbitMQ 创建订单时默认写入 `PENDING_PAYMENT`。
- 新增支付状态枚举 `PaymentStatus`。
- 新增支付渠道枚举 `PaymentProviderType`。
- 新增 `payment_order` 支付单表。
- 新增 `payment_webhook_event` webhook 幂等表。
- 新增创建支付接口。
- 新增 Mock 支付 provider。
- 新增 Mock webhook 接口。
- 支付成功后事务更新支付单和订单。
- 重复 webhook 不重复处理。
- 新增超时未支付取消任务。
- 订单过期后回补 MySQL 库存和 Redis 库存。
- 更新 README 和验收说明。

### 第一版先不做

- 不直接接入 Stripe。
- 不做真实 Bancontact 支付。
- 不做退款流程。
- 不做对账任务。
- 不做 RabbitMQ 延迟取消。
- 不支持复杂的多次支付尝试。
- 不支持同一用户在订单过期后再次抢购同一个 offer。

这些能力后续可以在阶段三增强版或阶段四之后继续演进。

## 3. 核心概念

### 订单

订单是业务交易记录，表示用户抢到了某个 offer。

订单不应该完全由前端提交。前端最多提交 `offerId` 或 `orderId`，用户身份来自 JWT，金额和币种来自数据库。

订单创建时需要保存金额快照：

```text
pay_amount = 下单时 offers.price_amount
currency = EUR
```

支付时从订单快照读取金额和币种，不再读取 offer 当前价格。这样可以避免用户下单后、支付前，管理员修改 offer 价格导致支付金额变化。

### 支付单

支付单表示某笔订单的一次支付请求。

订单关注“用户买了什么、当前业务状态是什么”。

支付单关注“这次支付多少钱、用哪个 provider、第三方支付编号是什么、支付状态是什么”。

### Webhook

Webhook 是支付 provider 主动调用我们后端的接口。

不是我们循环询问 provider，而是 provider 在支付成功、失败、取消等事件发生后主动通知我们。

### 幂等

幂等表示同一个事件重复执行多次，最终业务结果和执行一次一致。

支付 webhook 可能重复到达，所以必须通过 `provider + event_id` 去重。

### 事务

事务用于保证一组数据库操作要么一起成功，要么一起失败。

支付成功时至少要在一个事务中完成：

```text
payment_order -> PAID
orders -> PAID
payment_webhook_event -> PROCESSED
```

## 4. 订单状态机

第一版重点支持：

```text
PENDING_PAYMENT -> PAID
PENDING_PAYMENT -> EXPIRED
PENDING_PAYMENT -> CANCELLED
```

预留后续状态：

```text
PAID -> REFUNDING -> REFUNDED
```

建议保留当前 `orders.status` 的 `tinyint` 字段，先在代码层用枚举封装。

```text
1 PENDING_PAYMENT
2 PAID
3 CANCELLED
4 EXPIRED
5 REFUNDING
6 REFUNDED
```

状态流转规则：

```text
只有 PENDING_PAYMENT 可以被支付成功改为 PAID。
只有 PENDING_PAYMENT 可以被用户取消改为 CANCELLED。
只有 PENDING_PAYMENT 可以被超时任务改为 EXPIRED。
PAID 订单不能被超时任务取消。
EXPIRED / CANCELLED 订单不能再支付成功。
```

RabbitMQ Consumer 当前创建订单的位置在 `FlashSaleOrderConsumer`，后续创建 `Order` 时需要明确设置：

```text
status = PENDING_PAYMENT
pay_type = provider code or default value
```

## 5. 支付状态

`payment_order.status` 建议使用 `varchar`，表达更清晰。

第一版状态：

```text
CREATED
PENDING
PAID
FAILED
CANCELLED
EXPIRED
```

状态含义：

```text
CREATED    支付单已创建，但还未向 provider 发起支付
PENDING    已向 provider 发起支付，等待支付结果
PAID       provider 通知支付成功
FAILED     provider 通知支付失败
CANCELLED  用户或 provider 取消支付
EXPIRED    订单超时，支付单同步过期
```

第一版 Mock 可以简化为：

```text
CREATED -> PENDING -> PAID
PENDING -> EXPIRED
```

## 6. 数据库设计

### orders 表调整

当前 `orders` 表已有：

```text
id
user_id
offer_id
pay_type
status
create_time
pay_time
use_time
refund_time
update_time
```

建议第一版增加：

```sql
ALTER TABLE orders
  ADD COLUMN pay_amount bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'order payment amount in minor currency unit',
  ADD COLUMN currency varchar(8) NOT NULL DEFAULT 'EUR' COMMENT 'order payment currency',
  ADD COLUMN expire_time timestamp NULL DEFAULT NULL COMMENT 'payment expiration time';
```

说明：

- `pay_amount` 是下单金额快照，来自创建订单时的 `offers.price_amount`。
- `currency` 第一版固定为 `EUR`。
- `expire_time` 用于超时取消扫描。
- 第一版不强制把 `status` 改成 varchar，避免扩大改造范围。
- 后续如果要提高可读性，可以把订单状态迁移为 varchar。

### payment_order 表

```sql
CREATE TABLE `payment_order` (
  `id` bigint(20) NOT NULL,
  `order_id` bigint(20) NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `provider` varchar(32) NOT NULL,
  `provider_payment_id` varchar(128) DEFAULT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL,
  `currency` varchar(8) NOT NULL DEFAULT 'EUR',
  `status` varchar(32) NOT NULL,
  `checkout_url` varchar(512) DEFAULT NULL,
  `expires_at` timestamp NULL DEFAULT NULL,
  `paid_at` timestamp NULL DEFAULT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_payment_order_order` (`order_id`),
  UNIQUE KEY `uniq_provider_payment` (`provider`, `provider_payment_id`),
  KEY `idx_payment_order_user` (`user_id`),
  KEY `idx_payment_order_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

说明：

- 第一版采用“一笔订单最多一笔有效支付单”。
- `amount` 使用最小货币单位，例如 euro cents。
- `currency` 固定为 `EUR`。
- `provider_payment_id` 在 Mock 中可生成模拟值，Stripe 中对应 Checkout Session id 或 PaymentIntent id。
- `checkout_url` 在 Mock 中可以返回模拟 URL，Stripe 中对应 Checkout Session URL。

### payment_webhook_event 表

```sql
CREATE TABLE `payment_webhook_event` (
  `id` bigint(20) NOT NULL,
  `provider` varchar(32) NOT NULL,
  `event_id` varchar(128) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `provider_payment_id` varchar(128) DEFAULT NULL,
  `order_id` bigint(20) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `raw_payload` text,
  `error_message` varchar(512) DEFAULT NULL,
  `processed_at` timestamp NULL DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_provider_event` (`provider`, `event_id`),
  KEY `idx_webhook_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

说明：

- `provider + event_id` 是 webhook 幂等核心。
- 重复 webhook 命中唯一索引或查询到已处理记录时，直接返回成功。
- `raw_payload` 保存原始事件内容，便于排查。

## 7. API 设计

### 创建支付

```text
POST /payments/orders/{orderId}
Authorization: Bearer <access-token>
```

请求体第一版可以为空，或者传一个 provider：

```json
{
  "provider": "MOCK"
}
```

后端校验：

```text
订单存在。
订单属于当前用户。
订单状态是 PENDING_PAYMENT。
订单没有过期。
订单金额快照 pay_amount 大于 0。
金额从 orders.pay_amount 读取，不信任前端金额，也不再读取 offer 当前价格。
币种从 orders.currency 读取，第一版固定 EUR。
```

响应：

```json
{
  "success": true,
  "data": {
    "paymentOrderId": 123,
    "orderId": 456,
    "provider": "MOCK",
    "providerPaymentId": "mock_pay_456",
    "amount": 475,
    "currency": "EUR",
    "status": "PENDING",
    "checkoutUrl": "http://localhost:8080/payments/mock/checkout/mock_pay_456"
  }
}
```

### Mock 支付成功 webhook

```text
POST /payments/webhooks/mock
```

安全边界：

```text
Mock webhook 只用于 local / test profile。
如果 dev 环境也需要打开，必须要求请求头携带 X-Mock-Webhook-Secret。
生产环境不暴露 Mock webhook。
```

请求体：

```json
{
  "eventId": "mock_evt_001",
  "eventType": "payment.succeeded",
  "providerPaymentId": "mock_pay_456",
  "orderId": 456,
  "amount": 475,
  "currency": "EUR"
}
```

后端处理：

```text
在事务中先插入 provider + eventId 事件记录，抢占处理权。
如果唯一索引冲突，说明 webhook 已处理或正在处理，直接返回成功。
查询 payment_order。
校验 orderId、amount、currency。
检查订单状态是否仍为 PENDING_PAYMENT。
条件更新 payment_order 为 PAID。
条件更新 orders 为 PAID。
记录 webhook event 为 PROCESSED。
返回成功。
```

并发保护：

```text
UPDATE orders
SET status = PAID, pay_time = now()
WHERE id = ? AND status = PENDING_PAYMENT
```

如果条件更新影响行数为 0，说明订单可能已经 PAID、EXPIRED 或 CANCELLED，不能继续无条件改状态。

### 查询订单支付状态

可选但建议第一版加入，便于验收：

```text
GET /payments/orders/{orderId}
Authorization: Bearer <access-token>
```

返回：

```json
{
  "orderId": 456,
  "orderStatus": "PAID",
  "paymentStatus": "PAID",
  "provider": "MOCK",
  "amount": 475,
  "currency": "EUR"
}
```

## 8. 类设计

建议新增包：

```text
com.flashsale.platform.payment
```

第一版可以细分：

```text
payment
  controller
    PaymentController
    PaymentWebhookController

  dto
    CreatePaymentRequest
    CreatePaymentResponse
    MockPaymentWebhookRequest
    PaymentStatusResponse

  entity
    PaymentOrder
    PaymentWebhookEvent

  enums
    OrderStatus
    PaymentStatus
    PaymentProviderType

  mapper
    PaymentOrderMapper
    PaymentWebhookEventMapper

  provider
    PaymentProvider
    MockPaymentProvider
    PaymentProviderResult

  service
    PaymentService
    PaymentWebhookService
    OrderTimeoutService
```

如果希望保持当前项目风格，也可以先放在现有顶层包：

```text
controller
entity
mapper
service
service.impl
```

推荐第一版不要过度重构包结构，优先把闭环跑通。

### PaymentProvider

```java
public interface PaymentProvider {
    PaymentProviderType providerType();

    PaymentProviderResult createPayment(PaymentOrder paymentOrder);
}
```

Mock 实现：

```text
providerPaymentId = "mock_pay_" + paymentOrder.id
checkoutUrl = "/payments/mock/checkout/" + providerPaymentId
status = PENDING
```

Stripe 实现后续替换：

```text
providerPaymentId = checkout session id
checkoutUrl = checkout session url
status = PENDING
```

### PaymentService

核心方法：

```text
createPayment(orderId, userId, provider)
queryPaymentStatus(orderId, userId)
```

创建支付时需要保证幂等：

```text
如果订单已有 PENDING / PAID 支付单：
  返回已有支付单
否则：
  创建新支付单
  调用 provider
  更新 providerPaymentId、checkoutUrl、status
```

创建支付单时金额来源：

```text
payment_order.amount = orders.pay_amount
payment_order.currency = orders.currency
```

### PaymentWebhookService

核心方法：

```text
handleMockPaymentSucceeded(request)
```

后续 Stripe 接入时新增：

```text
handleStripeWebhook(rawPayload, signatureHeader)
```

## 9. 超时取消设计

第一版采用定时任务，不使用 RabbitMQ 延迟消息。

建议配置：

```yaml
app:
  payment:
    order-expire-minutes: 15
    timeout-scan-fixed-delay-ms: 60000
```

定时任务逻辑：

```text
每 60 秒扫描一次：
  查询 status = PENDING_PAYMENT 且 expire_time < now 的订单
  条件更新订单为 EXPIRED：
    UPDATE orders
    SET status = EXPIRED
    WHERE id = ? AND status = PENDING_PAYMENT AND expire_time < now
  更新对应 payment_order 为 EXPIRED
  MySQL flash_sale_offers.stock + 1
  Redis flashsale:stock:{offerId} + 1
```

注意事项：

- 更新订单必须使用条件更新，避免把刚支付成功的订单误取消。
- 只有订单条件更新成功的那一次，才能回补 MySQL 库存和 Redis 库存。
- 定时任务重复扫描同一订单时不能重复加库存。
- 第一版不释放 Redis 用户资格，不允许同一用户在订单过期后再次抢购同一个 offer。
- 当前 `RedisReservationCompensationService` 会同时移除用户资格并回补 Redis 库存，不适合直接用于第一版订单过期补偿。
- 如果订单已变为 `PAID`，超时任务必须跳过。

## 10. 与当前阶段二链路的衔接

当前阶段二已经完成：

```text
Redis Lua 资格裁决
RabbitMQ 投递
Consumer 异步创建订单
MQ 重试和 DLQ
Redis 资格补偿
```

阶段三主要接在 Consumer 成功创建订单之后。

需要改造点：

```text
OrderServiceImpl.createOrder
  -> 保存订单时明确设置 status = PENDING_PAYMENT
  -> 保存订单金额快照 pay_amount 和 currency
  -> 设置 expire_time

FlashSaleOrderConsumer
  -> 创建 Order 对象时不再只设置 id、offerId、userId
  -> 可保持简单，由 OrderServiceImpl 补齐默认状态和过期时间

OfferServiceImpl.publishFlashSaleOffer
  -> rebuildFlashSaleOrderCache 第一版继续包含所有已有订单用户
```

`rebuildFlashSaleOrderCache` 当前会把某个 offer 下所有历史订单用户都放回 Redis 一人一单集合。阶段三后需要注意：

```text
第一版 PAID / PENDING_PAYMENT / EXPIRED / CANCELLED 用户都应该进入一人一单集合。
这和当前 user_id + offer_id 唯一索引保持一致。
```

第一版建议：

```text
EXPIRED / CANCELLED 后回补库存，但不释放同一用户再次抢购资格。
也就是说，第一版仍然保持 user_id + offer_id 一人一单的数据库约束。
PAID / PENDING_PAYMENT / EXPIRED / CANCELLED 都会阻止同一用户再次抢购同一个 offer。
```

因此重建 Redis 资格集合时，第一版可以继续加入该 offer 下所有已有订单用户：

```text
status in (PENDING_PAYMENT, PAID, EXPIRED, CANCELLED)
```

如果后续要支持“过期后用户可再次抢购”，需要先调整数据库唯一索引和 `OrderServiceImpl.createOrder` 的判重逻辑，否则 Redis 释放资格后，MySQL 仍会被 `idx_user_offer` 或历史订单查询挡住。

## 11. 推荐迭代顺序

### 迭代 3.1：订单状态机

任务：

- 新增 `OrderStatus`。
- 新增 `pay_amount`、`currency`、`expire_time` 字段。
- Consumer 创建订单时写入 `PENDING_PAYMENT`。
- Consumer 创建订单时写入金额快照和币种。
- README 说明订单状态。

验收：

```text
秒杀成功后 orders.status = 1。
orders.pay_amount 大于 0。
orders.currency = EUR。
orders.expire_time 不为空。
```

### 迭代 3.2：支付表和支付模型

任务：

- 新增 `PaymentOrder`。
- 新增 `PaymentWebhookEvent`。
- 新增 mapper。
- 更新 SQL 初始化脚本。

验收：

```text
项目启动不报 mapper / entity 错误。
数据库能创建 payment_order 和 payment_webhook_event。
```

### 迭代 3.3：创建支付接口

任务：

- 新增 `PaymentController`。
- 新增 `PaymentService.createPayment`。
- 新增 `MockPaymentProvider`。
- 创建支付单并返回 mock checkoutUrl。

验收：

```text
PENDING_PAYMENT 订单可以创建支付单。
非本人订单不能创建支付单。
PAID / EXPIRED 订单不能创建支付单。
重复调用创建支付接口返回同一笔有效支付单。
```

### 迭代 3.4：Mock webhook 和幂等

任务：

- 新增 Mock webhook 接口。
- 新增 webhook event 保存逻辑。
- `provider + event_id` 去重。
- 支付成功事务更新 payment_order 和 orders。
- Mock webhook 仅 local / test profile 可用，或通过 `X-Mock-Webhook-Secret` 保护。

验收：

```text
第一次 webhook 能把订单更新为 PAID。
重复 webhook 返回成功但不会重复处理。
金额不匹配时拒绝处理。
币种不是 EUR 时拒绝处理。
订单已 EXPIRED 时拒绝更新为 PAID。
```

### 迭代 3.5：超时取消和补偿

任务：

- 新增 `OrderTimeoutService`。
- 定时扫描超时 `PENDING_PAYMENT` 订单。
- 条件更新为 `EXPIRED`。
- 回补 MySQL 库存。
- 回补 Redis 库存。
- 保留 Redis 用户资格，第一版不允许同一用户再次抢购。

验收：

```text
超时未支付订单会变为 EXPIRED。
PAID 订单不会被过期任务取消。
过期后库存回补。
过期后用户资格仍保留。
同一用户再次抢购同一个 offer 会被一人一单拦截。
```

### 迭代 3.6：文档和手动验收脚本

任务：

- README 增加 Mock 支付闭环说明。
- 增加 curl 验收流程。
- 记录后续 Stripe 接入点。

验收：

```text
可以按 README 完成：
登录 -> 秒杀 -> 创建支付 -> Mock webhook -> 订单 PAID。
可以按 README 完成：
登录 -> 秒杀 -> 不支付 -> 超时 EXPIRED -> 库存补偿。
```

## 12. 手动验收流程草案

### 支付成功链路

```text
1. 管理员发布 flash sale offer。
2. 用户登录。
3. 用户提交秒杀请求。
4. RabbitMQ Consumer 创建 PENDING_PAYMENT 订单。
5. 用户调用 POST /payments/orders/{orderId}。
6. 系统返回 mock providerPaymentId。
7. 调用 POST /payments/webhooks/mock 模拟支付成功。
8. 查询 orders.status = PAID。
9. 查询 payment_order.status = PAID。
10. 重复调用同一个 webhook eventId。
11. 状态不重复变化，webhook 幂等生效。
```

### 超时取消链路

```text
1. 用户提交秒杀请求。
2. RabbitMQ Consumer 创建 PENDING_PAYMENT 订单。
3. 不调用支付。
4. 等待超过 expire_time。
5. 定时任务把订单更新为 EXPIRED。
6. payment_order 如果存在，也更新为 EXPIRED。
7. MySQL 库存回补。
8. Redis 库存回补。
9. Redis 用户资格保留。
10. 同一用户再次抢购同一个 offer 会被拦截。
```

## 13. 后续 Stripe 接入点

Mock 闭环完成后，再新增：

```text
StripePaymentProvider
StripePaymentProperties
Stripe webhook controller
Stripe signature verification
checkout.session.completed event handler
payment_intent.payment_failed event handler
```

Stripe Checkout Session 建议映射：

```text
client_reference_id = orderId
metadata.orderId = orderId
metadata.paymentOrderId = paymentOrderId
currency = eur
payment_method_types includes bancontact or automatic_payment_methods
```

Stripe webhook 处理时仍复用：

```text
PaymentWebhookService
payment_webhook_event 幂等表
payment_order 状态更新
orders 状态机
```

因此 Stripe 接入应该只是替换 provider 和 webhook adapter，不应该重写订单支付核心逻辑。

## 14. 风险和注意事项

- 不能信任前端传入的金额、币种、用户 id。
- 支付成功不能以客户端跳转为准，必须以后端 webhook 为准。
- webhook 可能重复到达，必须做幂等。
- 只有已经处理成功的重复 webhook 才能按成功返回；已失败或仍在处理中的重复事件不能伪装成成功。
- Mock webhook 只能用于本地或测试环境，不能在生产环境裸露。
- 订单和支付单状态要通过条件更新保护，避免并发下乱跳状态。
- 支付成功和订单成功必须在事务里一起更新。
- 超时取消必须跳过已支付订单。
- Redis 和 MySQL 库存补偿需要保持语义清晰，避免重复加库存。
- 第一版过期订单不释放同一用户再次抢购资格；如后续要释放资格，必须同步调整数据库唯一索引和订单判重逻辑。
- `rebuildFlashSaleOrderCache` 第一版可以继续重建该 offer 下所有已有订单用户，保证 Redis 和 MySQL 一人一单语义一致。

## 15. 阶段三第一版完成标准

```text
秒杀成功后生成 PENDING_PAYMENT 订单。
用户可以对 PENDING_PAYMENT 订单创建 Mock 支付单。
Mock webhook 可以把订单和支付单更新为 PAID。
重复成功 webhook 不会重复处理。
重复失败 webhook 不会被伪装成成功。
金额或币种不匹配的 webhook 不会更新订单。
超时未支付订单会自动 EXPIRED。
EXPIRED 后 MySQL 库存和 Redis 库存会补偿。
EXPIRED 后同一用户仍不能再次抢购同一个 offer。
PAID 订单不会被超时任务取消。
README 能讲清 Mock 支付闭环和后续 Stripe 替换点。
```
