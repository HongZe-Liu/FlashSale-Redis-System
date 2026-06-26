# Flash Sale Platform 项目改造计划

## 1. 项目新定位

当前项目将从原本的点评类教学项目，逐步改造成：

> 面向欧洲支付场景的高并发优惠券秒杀交易系统。

改造后的核心目标不是堆功能，而是围绕交易链路展示后端工程能力：

- 登录认证与权限控制
- Redis Lua 秒杀资格裁决
- RabbitMQ 异步削峰、重试、死信
- 订单状态机与支付流水
- Stripe / Bancontact / EUR 支付场景
- Webhook 签名校验与幂等处理
- 超时取消、库存回补与异常补偿
- Actuator / Prometheus / Grafana 可观测性
- Docker Compose、测试、压测与 README 工程化交付

## 2. 改造原则

本计划先定义方向，不一次性锁死所有实现细节。

每进入一个阶段，先讨论技术方案，再写代码。讨论内容包括：

- 是否与当前代码结构兼容
- 是否会破坏已有主链路
- 是否有更简单、更稳的阶段性实现
- 是否需要先补测试或文档
- 是否适合放进简历表达

默认原则：

- Redis 保留，但不再作为订单消息队列。
- RabbitMQ 替换 Redis Stream，承担异步订单消息。
- 删除或弱化点评社交模块，聚焦交易闭环。
- 支付优先使用 Stripe，面向比利时场景支持 Bancontact / EUR。
- 监控围绕登录、秒杀、MQ、订单、支付和补偿链路设计。
- 每个阶段都要有验收标准。

## 3. 目标模块结构

计划中的核心模块如下：

```text
auth
  登录、JWT、refresh token、权限、用户状态

catalog
  店铺、优惠券、秒杀券基础数据

seckill
  Redis Lua、库存预扣、一人一单、秒杀资格裁决

order
  订单创建、订单状态机、超时取消、库存补偿

payment
  支付单、Stripe/Bancontact、webhook、幂等、对账

mq
  RabbitMQ 下单消息、重试队列、死信队列、延迟取消

monitoring
  Actuator、Micrometer、Prometheus、Grafana、业务指标
```

计划删除或隐藏的模块：

```text
blog
follow
comments
upload
旧 MVC 拦截器体系
实验性的 RabbitMQ smoke test
未使用或重复的工具类
```

店铺和优惠券不建议删除，因为它们构成交易系统的商品域。

## 4. 总体目标架构

目标链路：

```text
用户登录
  -> 获取 access token 和 refresh token

用户请求秒杀
  -> Redis Lua 原子校验活动时间、库存、一人一单
  -> Redis 预扣库存并记录用户资格
  -> Java 发送 RabbitMQ 下单消息
  -> RabbitMQ Consumer 异步创建待支付订单
  -> MySQL 事务扣减最终库存并保存订单

用户发起支付
  -> 创建支付单
  -> 调用 Stripe 创建 Checkout Session 或 PaymentIntent
  -> 返回支付跳转地址

支付平台回调
  -> 校验 webhook 签名
  -> 按 provider_event_id 幂等处理
  -> 事务更新支付单和订单状态

超时未支付
  -> RabbitMQ 延迟消息或定时任务触发取消
  -> 订单状态变更为 EXPIRED / CANCELLED
  -> 库存和用户资格补偿

监控
  -> Prometheus 采集技术指标和业务指标
  -> Grafana 展示秒杀、MQ、订单、支付和补偿状态
```

## 5. 阶段一：项目边界清理

### 目标

把项目从点评系统收敛成交易系统，先修正基础一致性问题。

### 主要任务

- 明确新项目名称和 README 定位。
- 删除或隐藏博客、关注、评论、上传等非核心模块。
- 保留店铺、优惠券、秒杀券、订单、用户认证。
- 修复 Spring Security 与旧 `UserHolder` 混用问题。
- 修复 `JwtUtils` 直接读取环境变量导致的配置问题。
- 移除硬编码 MySQL、Redis、RabbitMQ、JWT 密钥。
- 清理旧 MVC 拦截器、重复缓存工具类、实验代码。
- 移除启动时自动发送 RabbitMQ 消息的 smoke test。
- 整理包结构，为后续 `auth / seckill / order / payment / mq` 演进做准备。

### 需要进一步讨论

- 非核心模块是物理删除，还是先保留但移除入口。
- 是否在第一阶段就调整包结构，还是先做最小变更。
- JWT 配置要继续用静态工具类，还是改成 Spring Bean。

### 验收标准

```text
项目可以正常启动。
登录、刷新 token、登出可用。
店铺、优惠券、秒杀券基础接口可用。
非核心点评模块不再干扰主交易链路。
配置可以通过 profile 或环境变量切换。
```

### 当前完成情况

```text
已完成：
- 项目定位已收缩为 flash-sale-platform。
- 旧点评类主链路已弱化，当前核心保留登录、商家、优惠、秒杀订单。
- 用户登录支持 email 验证码、JWT access token、refresh token。
- 数据库表已收敛为 tb_user / merchants / offers / flash_sale_offers / orders 等核心表。
- 秒杀活动支持管理员发布，发布时从 MySQL 预热 Redis。
- 秒杀下单支持 Redis Lua 原子裁决、Redis Stream 异步落库、一人一单和库存扣减。
- local profile 已指向 flash_sale_platform schema。
- 商家详情接口返回结构已修正为单层 Result。
- Redis Stream 消费线程已支持应用关闭时优雅退出。
```

```text
阶段一仍建议补充：
- README 和接口文档持续同步。
- 最小自动化验收测试。
- Docker Compose 本地依赖编排。
```

## 6. 阶段二：Redis Stream 改 RabbitMQ

### 阶段二定位

阶段二先采用方案 A：

```text
Redis Lua 负责秒杀资格裁决和 Redis 预占。
Java 在 Lua 成功后投递 RabbitMQ。
RabbitMQ Consumer 异步创建 MySQL 订单。
如果 MQ 投递明确失败，立即补偿 Redis 库存和用户资格。
```

这个方案不是最终的金融级可靠消息方案，而是当前项目第二阶段的可控演进版本。
它的价值是先把 RabbitMQ 削峰、手动 ACK、重试、死信和补偿链路跑通，再逐步理解每个组件为什么存在。

后续如果要进一步强化生产级一致性，再演进到：

```text
方案 B：Redis pending reservation + 后台补偿任务
方案 C：数据库 outbox / reservation 表 + 后台可靠投递
```

### 改造前

```text
用户请求秒杀
  -> Redis Lua 原子裁决
  -> Lua XADD stream.flashsale.orders
  -> Redis Stream Consumer
  -> MySQL 创建订单
```

### 改造后

```text
用户请求秒杀
  -> Redis Lua 原子裁决和预占
  -> Java 发送 RabbitMQ 消息
  -> RabbitMQ Consumer 手动 ACK 消费
  -> MySQL 事务创建订单
```

详细链路：

```text
FlashSaleController
  -> OrderService.placeFlashSaleOrder
  -> 生成 orderId
  -> 执行 flash-sale.lua
      - 校验活动时间
      - 校验 Redis 库存
      - 校验一人一单
      - 扣 Redis 库存
      - 写入 Redis 用户资格集合
  -> FlashSaleOrderProducer 投递 RabbitMQ
      - publisher confirm
      - mandatory return
  -> 投递成功：返回 orderId
  -> 投递失败：补偿 Redis 预占，返回失败

RabbitMQ Consumer
  -> 接收 FlashSaleOrderMessage
  -> 二次校验 Redis 用户资格
  -> 调用 createOrder(order)
  -> MySQL 事务扣最终库存并保存订单
  -> 成功后 basicAck
  -> 失败进入 retry queue
  -> 超过重试次数进入 DLQ
```

### RabbitMQ 拓扑

建议使用三组 exchange / queue：

```text
flashsale.order.exchange
  routing key: flashsale.order.create
  -> flashsale.order.create.queue

flashsale.order.retry.exchange
  routing key: flashsale.order.retry
  -> flashsale.order.create.retry.queue
       x-message-ttl: 5000 或 10000
       x-dead-letter-exchange: flashsale.order.exchange
       x-dead-letter-routing-key: flashsale.order.create

flashsale.order.dead.exchange
  routing key: flashsale.order.dead
  -> flashsale.order.create.dlq
```

消费失败后的流转：

```text
主队列消费失败
  -> 投递到 retry queue
  -> retry queue TTL 到期
  -> 自动死信回主 exchange
  -> 回到主队列再次消费

超过最大重试次数
  -> 投递到 dead exchange
  -> 进入 DLQ
  -> 执行 Redis 资格补偿
  -> 记录错误日志
```

### 一致性边界

方案 A 的核心风险是：

```text
Redis Lua 成功
MQ 投递失败
```

因为 Redis 预扣和 RabbitMQ 投递不是一个原子事务，所以需要补偿。

补偿原则：

```text
只有用户资格仍然存在时，才回滚库存。
补偿必须幂等。
补偿建议用 Lua 完成，避免重复执行导致库存加多。
```

补偿逻辑：

```text
如果 SREM flashsale:order:{offerId} userId 成功：
  INCR flashsale:stock:{offerId}
否则：
  不回补库存
```

还需要处理 publisher confirm 超时的不确定状态：

```text
RabbitMQ 可能已经收到消息
Java 没有收到 confirm
Java 执行了 Redis 补偿
Consumer 后续又消费到了消息
```

因此 Consumer 创建订单前要二次校验 Redis 资格：

```text
如果 flashsale:order:{offerId} 不包含 userId：
  说明资格已经被补偿或失效
  直接 ACK，不创建订单
```

最终兜底仍然依赖 MySQL：

```text
orders 唯一索引：user_id + offer_id
flash_sale_offers 条件扣减：stock > 0
订单创建事务：扣库存和保存订单必须在同一个事务中完成
```

### 拆分迭代计划

#### 迭代 2.1：移除 Redis Stream 写入

目标：

```text
Lua 只负责秒杀资格裁决和 Redis 预占，不再负责写消息队列。
```

任务：

- 修改 `flash-sale.lua`，删除 `XADD stream.flashsale.orders`。
- 保留活动时间、库存、一人一单、Redis 预扣逻辑。
- `OrderService.placeFlashSaleOrder` 暂时仍返回 `orderId`，为下一步接 MQ 做准备。

需要理解：

```text
为什么 Redis 适合做入口裁决？
为什么消息队列不应该继续放在 Redis Stream？
Lua 原子性覆盖了哪些操作？
Lua 原子性没有覆盖哪些操作？
```

验收标准：

```text
Lua 成功后 Redis 库存会减少。
Lua 成功后用户会进入 flashsale:order:{offerId} 集合。
Redis Stream 不再收到新订单消息。
```

#### 迭代 2.2：新增 RabbitMQ 基础配置

目标：

```text
建立主队列、重试队列和死信队列，为异步下单链路准备基础设施。
```

任务：

- 新增 RabbitMQ 常量或配置属性。
- 新增 exchange、queue、binding Bean。
- 配置 JSON message converter。
- 配置 listener manual ack。
- 配置 publisher confirm 和 mandatory return。

需要理解：

```text
exchange、queue、routing key 分别是什么？
为什么主队列、重试队列、死信队列要分开？
manual ack 和 auto ack 的区别是什么？
publisher confirm 解决什么问题？
mandatory return 解决什么问题？
```

验收标准：

```text
应用启动后 RabbitMQ 中能看到主队列、重试队列、DLQ。
配置类可以单独阅读并讲清每个队列的作用。
```

#### 迭代 2.3：新增秒杀订单消息和 Producer

目标：

```text
Lua 成功后由 Java 投递秒杀订单消息到 RabbitMQ。
```

任务：

- 新增 `FlashSaleOrderMessage` DTO。
- 新增 `FlashSaleOrderProducer`。
- Producer 发送消息时携带 `orderId`、`offerId`、`userId`、`createdAt`。
- 使用 publisher confirm 判断投递结果。
- unroutable message 要视为投递失败。

需要理解：

```text
为什么消息里必须带 orderId？
为什么不能等 Consumer 再生成 orderId？
confirm 成功代表什么？
return callback 代表什么？
confirm 超时为什么是不确定状态？
```

验收标准：

```text
秒杀成功后 RabbitMQ 主队列能收到消息。
接口返回 orderId。
关闭 RabbitMQ 或制造路由失败时，Producer 能识别投递失败。
```

#### 迭代 2.4：新增 Redis 补偿能力

目标：

```text
当 MQ 投递失败时，回滚 Redis 预扣库存和用户资格。
```

任务：

- 新增 Redis 补偿 Lua 脚本。
- 新增 `RedisReservationCompensationService`。
- `OrderService.placeFlashSaleOrder` 在 MQ 投递失败时调用补偿。
- 补偿日志要包含 `orderId`、`offerId`、`userId` 和失败原因。

需要理解：

```text
为什么补偿必须幂等？
为什么不能无条件 INCR 库存？
为什么投递失败后不能长期占用用户资格？
```

验收标准：

```text
MQ 投递失败后 Redis 库存会恢复。
MQ 投递失败后用户资格会移除。
重复执行补偿不会把库存加多。
```

#### 迭代 2.5：新增 RabbitMQ Consumer 创建订单

目标：

```text
RabbitMQ Consumer 异步创建 MySQL 订单，替换 Redis Stream 消费线程。
```

任务：

- 新增 `FlashSaleOrderConsumer`。
- 使用 `@RabbitListener` 监听主队列。
- Consumer 创建订单前二次校验 Redis 用户资格。
- 复用现有 `createOrder(order)` 事务。
- 订单创建成功后手动 ACK。
- 移除 `OrderServiceImpl` 中 Redis Stream 消费线程、pending-list 和 Stream DLQ 代码。

需要理解：

```text
为什么 Consumer 要二次校验 Redis 用户资格？
为什么订单事务成功后才能 ACK？
为什么数据库唯一索引仍然必须保留？
```

验收标准：

```text
秒杀接口返回 orderId。
RabbitMQ 消息被消费。
orders 表生成订单。
flash_sale_offers 库存减少。
同一用户重复抢购不会生成重复订单。
```

#### 迭代 2.6：消费失败重试和死信

目标：

```text
Consumer 失败时支持有限重试，超过重试次数后进入 DLQ。
```

任务：

- 消费失败时读取并递增 retry count。
- 未超过重试上限时投递到 retry exchange。
- 超过重试上限时投递到 dead exchange。
- DLQ 消息记录失败原因、重试次数、原始消息内容。
- 进入 DLQ 后补偿 Redis 用户资格，必要时回补库存。

需要理解：

```text
为什么不能无限重试？
retry queue 的 TTL + DLX 是怎么让消息回到主队列的？
什么是毒消息？
DLQ 是排查问题用的，不是正常业务队列。
```

验收标准：

```text
人为制造消费异常时，消息会进入 retry queue。
重试后可以重新回到主队列。
超过最大次数后消息进入 DLQ。
DLQ 消息包含可排查的错误信息。
```

#### 迭代 2.7：测试、文档和面试表达

目标：

```text
把实现结果转化成可验证、可讲述的工程能力。
```

任务：

- 补充最小自动化测试或手动验收脚本。
- README 增加 RabbitMQ 秒杀链路说明。
- 文档记录方案 A 的优点、不足和演进路径。
- 整理面试表达和可能追问。

需要理解：

```text
方案 A 解决了什么问题？
方案 A 没解决什么问题？
为什么它不是最终生产级可靠消息方案？
如何演进到 Redis pending reservation？
如何演进到数据库 outbox？
```

验收标准：

```text
并发秒杀不超卖。
同一用户不能重复下单。
RabbitMQ 消费端支持手动 ACK。
消息消费失败可以重试。
毒消息可以进入死信队列。
MQ 投递失败不会长期占用 Redis 库存和用户资格。
能清楚讲出方案 A 的边界和后续演进。
```

### 阶段二面试表达草案

中文版本：

> 第二阶段我将原来的 Redis Stream 异步下单链路替换为 RabbitMQ。Redis Lua 只负责高并发入口下的活动时间校验、库存预扣和一人一单资格记录；Lua 成功后由 Java 通过 RabbitMQ 投递秒杀订单消息。由于 Redis 预扣和 MQ 投递不是原子操作，我使用 publisher confirm 和 mandatory return 识别投递失败，并通过补偿 Lua 幂等回滚 Redis 库存和用户资格。Consumer 使用手动 ACK，订单事务成功后才确认消息；消费失败进入重试队列，超过重试上限进入死信队列。最终通过 Redis 资格校验、MySQL 唯一索引和条件扣库存共同兜底一致性。

英文版本：

> In phase two, I replaced the Redis Stream based asynchronous order pipeline with RabbitMQ. Redis Lua is responsible only for atomic flash-sale validation, stock pre-deduction, and one-user-one-order reservation. After Lua succeeds, the application publishes an order message to RabbitMQ. Since Redis reservation and MQ publishing are not atomic, publisher confirms and mandatory returns are used to detect publishing failures, and an idempotent compensation Lua script rolls back Redis stock and user reservation. The consumer uses manual acknowledgements and only ACKs after the order transaction succeeds. Failed messages are routed to retry queues and eventually to a dead-letter queue after the retry limit. MySQL unique constraints and conditional stock deduction remain the final consistency safeguards.

## 7. 阶段三：订单与支付闭环

### 目标

让秒杀从抢券链路升级为完整交易链路。

### 订单状态

计划引入订单状态机：

```text
PENDING_PAYMENT
PAID
CANCELLED
EXPIRED
REFUNDING
REFUNDED
```

典型状态流转：

```text
PENDING_PAYMENT -> PAID
PENDING_PAYMENT -> CANCELLED
PENDING_PAYMENT -> EXPIRED
PAID -> REFUNDING
REFUNDING -> REFUNDED
```

### 支付模块设计

计划新增支付抽象：

```text
PaymentProvider
  -> MockPaymentProvider
  -> StripePaymentProvider
```

其中：

- `MockPaymentProvider` 用于本地开发和自动化测试。
- `StripePaymentProvider` 用于展示欧洲支付集成能力。
- 文档中说明支持 Bancontact / EUR 场景。
- Mollie / Adyen 作为后续可替换扩展。

### 主要任务

- 新增支付单表 `payment_order`。
- 新增支付创建接口。
- 创建 Stripe Checkout Session 或 PaymentIntent。
- 使用 EUR 作为支付币种。
- 保存 provider payment id。
- 新增 Stripe webhook 接口。
- 校验 webhook 签名。
- 按 provider event id 做回调幂等。
- 支付成功后事务更新订单和支付单。
- 支付失败或取消时记录支付状态。
- 支持订单超时未支付取消。
- 取消后回补库存和用户资格。
- 设计支付对账任务。

### 需要进一步讨论

- 使用 Stripe Checkout Session 还是 PaymentIntent。
- 本地测试是否先以 Mock 支付完成闭环，再接 Stripe。
- 支付成功后是否立即发放券，还是只更新订单状态。
- 退款功能是否进入第一版支付闭环。
- 对账任务是查询 Stripe API，还是先做 Mock 查询。

### 验收标准

```text
秒杀成功后生成待支付订单。
用户可以对待支付订单发起支付。
支付回调可以更新订单为已支付。
重复 webhook 不会重复处理。
超时未支付订单可以自动取消。
取消后库存和用户资格有补偿。
```

## 8. 阶段四：监控与可观测性

### 目标

让系统具备基础线上可观测能力，能看见核心交易链路的状态。

### 技术栈

```text
Spring Boot Actuator
Micrometer
Prometheus
Grafana
```

### 技术指标

- HTTP QPS
- HTTP P95 / P99 延迟
- HTTP 4xx / 5xx
- JVM 内存
- GC
- 线程数
- 数据库连接池
- Redis 连接与命令耗时
- RabbitMQ 队列积压

### 业务指标

- 登录成功次数
- 登录失败次数
- 验证码发送次数
- 验证码限流次数
- 秒杀请求数
- 秒杀成功数
- 秒杀失败原因分布
- MQ 投递成功 / 失败次数
- MQ 消费成功 / 失败次数
- 重试消息数量
- 死信消息数量
- 订单创建成功 / 失败次数
- 支付发起次数
- 支付成功 / 失败次数
- webhook 接收次数
- webhook 重复次数
- 超时取消订单数
- Redis 库存补偿次数

### 主要任务

- 新增 `BusinessMetrics` 统一封装业务指标。
- 接入 Actuator。
- 暴露 Prometheus 指标端点。
- 配置 Prometheus。
- 配置 Grafana Dashboard。
- README 增加监控说明和截图。

### 需要进一步讨论

- 业务指标使用 counter、timer 还是 gauge。
- RabbitMQ 队列积压从 RabbitMQ exporter 采集，还是应用内采集。
- Grafana Dashboard 是否纳入项目文件。
- 是否为 DLQ、支付失败、补偿失败设计告警规则。

### 验收标准

```text
Prometheus 可以抓取应用指标。
Grafana 可以展示 HTTP、秒杀、MQ、订单、支付核心指标。
压测时可以观察吞吐、失败原因和队列积压。
异常链路有可定位的指标和日志。
```

## 9. 阶段五：工程化交付

### 目标

让项目可运行、可验证、可展示。

### 主要任务

- 新增 Dockerfile。
- 新增 Docker Compose。
- Compose 中包含：
  - MySQL
  - Redis
  - RabbitMQ
  - Prometheus
  - Grafana
  - App
- 新增 `.env.example`。
- 整理数据库初始化脚本。
- 补充 README。
- 补充架构图。
- 补充核心流程图。
- 补充接口示例。
- 补充测试说明。
- 补充压测方案和结果。

### 测试计划

重点测试：

- 登录验证码限流
- refresh token 轮换
- refresh token 复用检测
- 秒杀 Lua 分支
- 一人一单
- 库存不超卖
- MQ 消费幂等
- MQ 重试和 DLQ
- 支付创建幂等
- webhook 幂等
- 支付超时取消
- 库存和资格补偿

### 压测计划

重点验证：

```text
并发请求下不超卖。
同一用户不能重复下单。
MQ 积压可以逐步消费完成。
支付回调重复不会导致状态错乱。
系统关键指标可以在 Grafana 中观察。
```

### 验收标准

```text
可以通过 Docker Compose 启动核心环境。
README 能让面试官理解项目背景、架构和启动方式。
自动化测试覆盖核心正确性。
压测报告证明不超卖、不重复下单。
监控截图证明系统可观察。
```

## 10. 推荐开发顺序

实际执行时建议按以下顺序推进：

```text
1. 清理项目边界
2. 修复认证和配置硬伤
3. Redis Stream 替换为 RabbitMQ
4. 订单状态机
5. Mock 支付闭环
6. Stripe / Bancontact 支付集成
7. 支付超时取消与补偿
8. 监控指标
9. Docker Compose
10. 测试、压测、README
```

每一步开始前，先讨论具体技术方案，再进行代码改造。

## 11. 简历表达草案

中文版本：

> 设计并实现面向欧洲支付场景的高并发优惠券秒杀交易系统。基于 Redis Lua 完成库存预扣、一人一单和活动时间的原子校验，使用 RabbitMQ 实现异步削峰、手动 ACK、重试和死信队列；订单侧通过状态机、数据库唯一索引、支付流水和幂等机制保证交易一致性；集成 Stripe / Bancontact 支付回调，支持 webhook 签名校验、重复回调处理、超时取消和库存补偿；通过 Prometheus + Grafana 监控秒杀、MQ、订单和支付核心指标。

英文版本：

> Built a high-concurrency offer flash-sale transaction system for a European payment scenario. Redis Lua is used for atomic stock pre-deduction, one-user-one-order validation, and sale-time checks, while RabbitMQ handles asynchronous order creation with manual acknowledgements, retries, and dead-letter queues. The order and payment modules use state machines, database constraints, payment records, and idempotency controls to ensure transaction consistency. Integrated Stripe/Bancontact payment callbacks with webhook signature verification, duplicate event handling, timeout cancellation, and stock compensation. Added Prometheus and Grafana dashboards to monitor flash-sale, MQ, order, and payment metrics.

## 12. 暂不立即决定的事项

以下问题在具体阶段开始前再讨论：

- 是否物理删除所有点评模块。
- 是否采用 outbox 表做 MQ 可靠投递。
- Stripe 采用 Checkout Session 还是 PaymentIntent。
- 是否第一版支持退款。
- 是否引入 Flyway / Liquibase。
- 是否升级 Spring Boot 版本。
- 是否引入 Testcontainers。
- 是否加入 Kubernetes 部署示例。

这些事项不影响第一版路线图，但会影响具体实现成本。
