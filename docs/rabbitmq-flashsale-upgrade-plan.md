# 阶段二 RabbitMQ 秒杀链路升级方案

定稿日期：2026-06-24

## 1. 方案结论

阶段二采用方案 A+：

```text
Redis Lua 负责秒杀入口裁决和 Redis 预占。
Java 在 Lua 成功后投递 RabbitMQ。
RabbitMQ Consumer 异步创建 MySQL 订单。
MQ 投递明确失败时，立即补偿 Redis 库存和用户资格。
Consumer 创建订单前二次校验 Redis 用户资格。
MySQL 唯一索引和条件扣库存作为最终兜底。
```

这个方案不是最终的金融级可靠消息方案，而是当前项目第二阶段的阶段性生产增强方案。
目标是先完成 Redis Stream 到 RabbitMQ 的迁移，并把异步下单、手动 ACK、重试、死信、补偿和幂等这些核心工程点跑通。

后续如果需要进一步强化可靠性，再演进到：

```text
方案 B：Redis pending reservation + 后台补偿任务
方案 C：数据库 outbox / reservation 表 + 后台可靠投递
```

## 2. 改造目标

当前链路：

```text
用户请求秒杀
  -> Redis Lua 原子裁决
  -> Lua XADD stream.flashsale.orders
  -> Redis Stream Consumer
  -> MySQL 创建订单
```

升级后链路：

```text
用户请求秒杀
  -> Redis Lua 原子裁决和预占
  -> Java Producer 投递 RabbitMQ
  -> RabbitMQ Consumer 手动 ACK 消费
  -> MySQL 事务创建订单
```

阶段二只聚焦 RabbitMQ 替换 Redis Stream，不同时引入订单状态机、支付闭环、监控、Docker Compose 或 outbox。

## 3. 本阶段范围

本阶段要做：

- 修改 `flash-sale.lua`，删除 Redis Stream `XADD`。
- 新增 RabbitMQ exchange、queue、routing key、binding 配置。
- 新增秒杀订单消息 DTO。
- 新增 RabbitMQ Producer。
- 新增 Redis 预占补偿 Lua 和补偿服务。
- 新增 RabbitMQ Consumer。
- 使用 manual ack，订单事务成功后再 ACK。
- 消费失败进入 retry queue。
- 超过重试上限进入 DLQ。
- 保留数据库唯一索引兜底一人一单。
- 保留数据库 `stock > 0` 条件扣减兜底不超卖。
- 移除 Redis Stream 消费线程、pending-list 和 Stream DLQ 逻辑。

本阶段暂不做：

- 不做数据库 outbox 表。
- 不做 Redis pending reservation 后台扫描。
- 不做订单状态机。
- 不做支付模块。
- 不做支付超时取消。
- 不做大规模包结构重构。
- 不做 Prometheus / Grafana 监控。
- 不做 Docker Compose 全量编排。

## 4. 目标架构

```text
FlashSaleController
  -> OrderService.placeFlashSaleOrder
  -> RedisIdWorker 生成 orderId
  -> flash-sale.lua 原子裁决
      - 校验活动时间
      - 校验 Redis 库存
      - 校验一人一单
      - 扣 Redis 库存
      - 记录用户资格
  -> FlashSaleOrderProducer 投递 RabbitMQ
      - publisher confirm
      - mandatory return
  -> 投递成功：返回 orderId
  -> 投递失败：补偿 Redis 预占，返回失败

RabbitMQ
  -> 主队列承接下单消息
  -> retry queue 承接消费失败重试
  -> DLQ 保存超过重试上限的失败消息

FlashSaleOrderConsumer
  -> 接收 FlashSaleOrderMessage
  -> 二次校验 Redis 用户资格
  -> 调用 createOrder(order)
  -> MySQL 事务扣最终库存并保存订单
  -> 成功后 basicAck
  -> 失败后进入 retry queue
  -> 超过重试次数进入 DLQ
```

## 5. RabbitMQ 拓扑

建议使用三组 exchange / queue：

```text
flashsale.order.exchange
  type: direct
  routing key: flashsale.order.create
  queue: flashsale.order.create.queue
  x-dead-letter-exchange: flashsale.order.dead.exchange
  x-dead-letter-routing-key: flashsale.order.dead

flashsale.order.retry.exchange
  type: direct
  routing key: flashsale.order.retry
  queue: flashsale.order.create.retry.queue
  x-message-ttl: 5000
  x-dead-letter-exchange: flashsale.order.exchange
  x-dead-letter-routing-key: flashsale.order.create

flashsale.order.dead.exchange
  type: direct
  routing key: flashsale.order.dead
  queue: flashsale.order.create.dlq
```

消费失败流转：

```text
主队列消费失败
  -> 投递到 retry exchange
  -> 进入 retry queue
  -> TTL 到期后死信回主 exchange
  -> 回到主队列再次消费

超过最大重试次数
  -> 投递到 dead exchange
  -> 进入 DLQ
  -> 记录失败原因、重试次数、原始消息
  -> 执行 Redis 资格补偿

框架级毒消息，例如消息体不是合法 JSON
  -> Spring 在进入业务 consumer 前拒绝消息
  -> RabbitMQ 根据主队列 DLX 投递到 dead exchange
  -> 进入 DLQ，保留原始坏消息，供人工排查
```

## 6. 核心一致性设计

### 6.1 Redis 入口裁决

Lua 脚本负责秒杀入口的原子操作：

```text
校验活动时间
校验库存
校验用户是否已抢过
扣减 Redis 库存
写入用户资格集合
```

Lua 成功后 Redis 状态变为：

```text
flashsale:stock:{offerId} - 1
flashsale:order:{offerId} 添加 userId
```

Lua 不再写 Redis Stream。

### 6.2 MQ 投递失败补偿

方案 A+ 的核心风险是：

```text
Redis Lua 成功
MQ 投递失败
```

因此 MQ 投递明确失败时，必须补偿 Redis 预占。

补偿原则：

```text
补偿必须幂等。
不能无条件 INCR 库存。
只有用户资格确实存在并被移除时，才回补库存。
```

补偿 Lua 逻辑：

```text
如果 SREM flashsale:order:{offerId} userId 成功：
  INCR flashsale:stock:{offerId}
  返回 1
否则：
  不回补库存
  返回 0
```

这样即使补偿重复执行，也不会把库存加多。

### 6.3 Confirm 超时的不确定状态

publisher confirm 超时不等于 RabbitMQ 一定没有收到消息。

可能出现：

```text
RabbitMQ 实际已经收到消息
Java 没有收到 confirm
Java 认为投递失败并补偿 Redis
Consumer 后续又消费到了消息
```

因此 Consumer 创建订单前必须二次校验 Redis 用户资格：

```text
如果 flashsale:order:{offerId} 包含 userId：
  继续创建订单

如果 flashsale:order:{offerId} 不包含 userId：
  说明资格已经被补偿或失效
  直接 ACK
  不创建订单
```

这会牺牲一部分极端情况下的用户体验，但可以避免已经补偿的消息继续落库。

### 6.4 MySQL 最终兜底

Redis 和 RabbitMQ 都不能作为最终一致性的唯一防线。

MySQL 仍然保留两层兜底：

```text
orders 唯一索引：user_id + offer_id
flash_sale_offers 条件扣减：stock > 0
```

订单创建事务必须保证：

```text
扣减数据库库存
保存订单
```

要么一起成功，要么一起失败。

## 7. 任务锚点与实施顺序

后续开发按 `T0` 到 `T7` 推进。
这些编号作为实现、复盘和面试准备的固定锚点。

```text
T0：改造前基线确认
T1：移除 Lua 中的 Redis Stream 写入
T2：新增 RabbitMQ 拓扑配置
T3：新增秒杀订单消息 DTO 和 Producer
T4：新增 Redis 补偿 Lua
T5：新增 RabbitMQ Consumer 创建订单
T6：实现重试队列和 DLQ
T7：最小验收测试和文档同步
```

当前推进状态：

```text
T0：已完成
T1：已完成
T2：已完成
T3：已完成
T4：已完成
T5：已完成
T6：已完成，主队列已增加 DLX 兜底框架级转换失败
T7：已完成基础文档同步，仍建议补充自动化测试和本地联调记录
```

### T0：改造前基线确认

目标：

```text
确认当前项目状态，避免边改边迷路。
```

任务：

- 查看当前 `git status`。
- 确认当前秒杀链路仍是 `flash-sale.lua -> Redis Stream -> OrderServiceImpl Stream Consumer`。
- 确认 RabbitMQ 依赖和基础配置是否已有。
- 记录当前可运行状态。

验收：

```text
知道哪些文件会被改。
知道当前 Redis Stream 逻辑在哪里。
不处理无关改动。
```

### T1：移除 Lua 中的 Redis Stream 写入

任务：

- 修改 `flash-sale.lua`。
- 删除 `XADD stream.flashsale.orders`。
- 保留活动时间、库存、一人一单和 Redis 预扣逻辑。

验收：

```text
Lua 成功后 Redis 库存减少。
Lua 成功后用户资格写入 Redis Set。
Redis Stream 不再产生新的订单消息。
```

### T2：新增 RabbitMQ 拓扑配置

任务：

- 新增 RabbitMQ 配置类。
- 声明主 exchange / queue / binding。
- 声明 retry exchange / queue / binding。
- 声明 dead exchange / DLQ / binding。
- 配置 JSON message converter。
- 配置 listener manual ack。
- 配置 publisher confirm 和 mandatory return。

验收：

```text
应用启动后 RabbitMQ 中能看到主队列、重试队列和 DLQ。
队列绑定关系正确。
```

### T3：新增秒杀订单消息 DTO 和 Producer

任务：

- 新增 `FlashSaleOrderMessage`。
- 新增 `FlashSaleOrderProducer`。
- 消息包含 `orderId`、`offerId`、`userId`、`createdAt`。
- Producer 等待 publisher confirm。
- unroutable message 视为投递失败。

验收：

```text
秒杀成功后 RabbitMQ 主队列能收到消息。
接口返回 orderId。
关闭 RabbitMQ 或制造路由失败时，Producer 能识别投递失败。
```

### T4：新增 Redis 补偿 Lua

任务：

- 新增补偿 Lua 脚本。
- 新增补偿服务。
- MQ 投递失败时调用补偿服务。
- 记录补偿日志。

验收：

```text
MQ 投递失败后 Redis 库存恢复。
MQ 投递失败后用户资格移除。
重复补偿不会导致库存增加多次。
```

### T5：新增 RabbitMQ Consumer 创建订单

任务：

- 新增 `FlashSaleOrderConsumer`。
- 使用 `@RabbitListener` 监听主队列。
- Consumer 创建订单前二次校验 Redis 用户资格。
- 复用 `createOrder(order)` 事务。
- 成功后手动 ACK。
- 移除 Redis Stream 消费线程和 pending-list 逻辑。

验收：

```text
RabbitMQ 消息能被消费。
orders 表生成订单。
flash_sale_offers 库存扣减。
同一用户重复消息不会生成重复订单。
```

### T6：实现重试队列和 DLQ

任务：

- 消费失败时记录 retry count。
- 未超过重试上限时投递 retry exchange。
- 超过重试上限时投递 dead exchange。
- DLQ 消息携带失败原因、重试次数和原始消息。
- 进入 DLQ 后补偿 Redis 用户资格。
- 主队列配置 DLX，兜底 Spring 消息转换阶段失败的毒消息。

验收：

```text
人为制造消费异常时，消息进入 retry queue。
TTL 到期后消息重新回到主队列。
超过最大重试次数后消息进入 DLQ。
DLQ 消息包含可排查的错误信息。
```

### T7：最小验收测试和文档同步

任务：

- README 增加 RabbitMQ 秒杀链路说明。
- 补充最小验收测试或手动测试脚本。
- 记录方案 A+ 的不足和后续演进路线。
- 整理面试表达。

验收：

```text
并发秒杀不超卖。
同一用户不能重复下单。
RabbitMQ 消费端支持手动 ACK。
消息消费失败可以重试。
毒消息可以进入 DLQ。
业务消费异常进入应用定义的重试和 DLQ 流程。
消息格式错误等框架级异常由主队列 DLX 直接送入 DLQ。
MQ 投递失败不会长期占用 Redis 库存和用户资格。
能清楚讲出方案 A+ 的边界和后续 outbox 演进。
```

## 8. 风险和不足

方案 A+ 仍然不是最终生产级可靠消息方案。

主要不足：

- Redis 预占和 MQ 投递不是原子事务。
- publisher confirm 超时是不确定状态。
- 应用可能在 Lua 成功后、投递 MQ 前宕机。
- 补偿记录主要在日志和 Redis 中，缺少数据库审计表。
- MQ 短暂抖动时，可能让已经抢到资格的用户变成失败。

当前阶段接受这些不足，原因是：

- 项目需要先从 Redis Stream 平滑迁移到 RabbitMQ。
- 当前阶段目标是建立可理解、可验证的异步下单链路。
- outbox 会提前引入更多表结构、任务调度和状态流转，容易和后续订单状态机、支付闭环混在一起。

后续增强方向：

```text
Redis pending reservation：
  Lua 成功后记录 pending reservation
  Producer 成功或 Consumer 成功后清理 pending
  后台任务扫描超时 pending 并补偿或重投

数据库 outbox：
  数据库记录 reservation / outbox event
  后台任务可靠投递 MQ
  Consumer 幂等创建订单
  对账任务修复异常状态
```

## 9. 面试表达

中文版本：

> 我把原来的 Redis Stream 异步下单链路替换成 RabbitMQ。Redis Lua 只负责秒杀入口的原子裁决，包括活动时间校验、库存预扣和一人一单资格记录；Lua 成功后由 Java 投递 RabbitMQ 秒杀订单消息。由于 Redis 预扣和 MQ 投递不是原子操作，我使用 publisher confirm 和 mandatory return 判断投递结果，投递明确失败时通过补偿 Lua 幂等回滚 Redis 库存和用户资格。Consumer 使用手动 ACK，订单事务成功后才确认消息，失败进入重试队列，超过重试上限进入死信队列。同时 Consumer 创建订单前会二次校验 Redis 用户资格，处理 confirm 超时导致的不确定消息。最终通过 MySQL 唯一索引和 stock > 0 条件扣减兜底一人一单和不超卖。

英文版本：

> I replaced the Redis Stream based asynchronous order pipeline with RabbitMQ. Redis Lua is responsible only for atomic flash-sale validation, including sale-time checks, Redis stock pre-deduction, and one-user-one-order reservation. After Lua succeeds, the application publishes an order message to RabbitMQ. Since Redis reservation and MQ publishing are not atomic, I use publisher confirms and mandatory returns to detect publishing failures, and an idempotent compensation Lua script rolls back Redis stock and user reservation when publishing clearly fails. The consumer uses manual acknowledgements and only ACKs after the order transaction succeeds. Failed messages are routed to retry queues and eventually to a dead-letter queue. Before creating an order, the consumer also verifies the Redis reservation to handle uncertain messages caused by confirm timeouts. MySQL unique constraints and conditional stock deduction remain the final safeguards against duplicate orders and overselling.
