# 轻量生产级测试改造方案

定稿日期：2026-06-26

## 1. 目标

本方案用于把当前项目的测试从默认启动测试升级为轻量生产级测试体系。

目标不是堆覆盖率，也不是做 demo 型单元测试，而是用尽量低的维护成本保护交易系统最容易出事故的地方：

- Redis Lua 秒杀入口裁决。
- RabbitMQ 异步下单、重试、死信和补偿。
- MySQL 订单、库存、支付单和 webhook 幂等约束。
- 支付单创建和订单状态机。
- Mock webhook 的幂等处理和异常保护。
- Controller 层认证、权限和接口契约。

测试体系需要同时满足两个要求：

```text
本地开发时反馈要快。
合并前关键链路要可信。
```

## 2. 当前测试现状

当前项目已有：

```text
src/test/java/com/flashsale/platform/FlashSalePlatformApplicationTests.java
```

但该测试类没有真实断言，也没有隔离外部依赖。

当前项目的主要外部依赖包括：

```text
MySQL
Redis
RabbitMQ
Redisson
Spring Security
MyBatis-Plus
Micrometer
```

因此不建议把所有测试都写成 `@SpringBootTest`。这样会导致测试慢、易受本机环境影响，并且失败原因不够清晰。

## 3. 测试原则

### 3.1 不做 toy 测试

测试必须围绕真实业务风险设计。优先测试：

```text
状态流转
幂等
事务边界
唯一约束
库存不超卖
重复消息
重复 webhook
外部依赖失败
异常补偿
权限边界
```

不优先测试：

```text
getter / setter
无分支 DTO
只验证 mock 被调用但没有业务断言的测试
为了覆盖率而复制实现细节的测试
```

### 3.2 快测试和真测试分开

使用两类测试：

```text
*Test  日常快速测试，mvn test 默认执行。
*IT    集成测试，mvn verify -Pintegration 执行。
```

默认本地开发先跑快速测试。

合并前、阶段验收或 CI 中再跑集成测试。

### 3.3 Mock 只用于系统边界

可以 mock：

```text
第三方支付 provider
邮件发送
业务指标 BusinessMetrics
时间敏感但非核心的调度触发器
```

不建议 mock：

```text
Redis Lua 行为
MySQL 唯一索引和事务
RabbitMQ 重试 / 死信拓扑
MyBatis SQL 映射
```

这些部分需要通过集成测试验证。

### 3.4 测试配置不依赖 application-local.yaml

测试不得依赖本机私有配置文件。

后续应新增：

```text
src/test/resources/application-test.yaml
```

测试环境配置只放测试专用配置，不读取本地数据库密码、个人 Redis 或个人 RabbitMQ 配置。

## 4. 测试分层

### 4.1 业务单元测试

用途：

```text
快速验证 service 内部业务分支。
不启动完整 Spring 容器。
不连接 MySQL / Redis / RabbitMQ。
```

建议技术：

```text
JUnit 5
Mockito
AssertJ
```

优先目标：

```text
PaymentServiceImplTest
PaymentWebhookServiceImplTest
OrderServiceImplTest
OfferServiceImplTest
```

这类测试重点不是模拟全部基础设施，而是锁住业务判断：

```text
订单不存在不能支付。
非本人订单不能支付。
过期订单不能支付。
异常金额不能支付。
已有 PENDING / PAID 支付单时可以幂等返回。
已有 CREATED 支付单时提示创建中。
provider 不存在时失败。
provider 异常时支付单标记 FAILED。
webhook 重复成功事件按幂等成功返回。
webhook 重复失败事件按失败返回。
webhook 金额 / 币种 / 订单号不匹配时不能改订单状态。
```

### 4.2 Web/API 契约测试

用途：

```text
验证 Controller 入参、认证用户、权限和 JSON 契约。
不连接真实数据库。
不验证 service 内部实现。
```

建议技术：

```text
@WebMvcTest
MockMvc
spring-security-test
@MockBean
```

优先目标：

```text
PaymentControllerTest
PaymentWebhookControllerTest
FlashSaleControllerTest
```

重点断言：

```text
未登录用户返回用户未登录。
登录用户的 userId 正确传给 service。
支付创建接口路径和请求体契约稳定。
mock webhook 在 test profile 下允许调用。
管理员接口需要 ADMIN 权限。
普通用户不能发布秒杀活动。
```

### 4.3 MySQL 集成测试

用途：

```text
验证真实 schema、MyBatis-Plus、XML Mapper、事务和唯一约束。
```

建议技术：

```text
Testcontainers MySQL
@SpringBootTest
@DynamicPropertySource
```

优先目标：

```text
OrderPersistenceIT
PaymentOrderPersistenceIT
PaymentWebhookPersistenceIT
```

重点断言：

```text
orders 一人一单唯一约束生效。
payment_order 同一 order_id 唯一约束生效。
payment_webhook_event 同一 provider + event_id 唯一约束生效。
OrderService.createOrder 成功时写入金额、币种、过期时间。
库存为 0 时数据库条件扣减失败。
事务失败时不会留下半更新状态。
```

### 4.4 Redis Lua 集成测试

用途：

```text
验证真实 Redis Lua 脚本，而不是在 Java 测试里复刻脚本逻辑。
```

建议技术：

```text
Testcontainers Redis
StringRedisTemplate
DefaultRedisScript
```

优先目标：

```text
FlashSaleRedisLuaIT
RedisReservationCompensationIT
```

重点断言：

```text
库存充足时扣减库存并写入用户资格。
库存不足返回 stock_not_enough。
重复用户返回 duplicate_order。
活动未开始返回 not_started。
活动已结束返回 ended。
活动未初始化返回 not_initialized。
补偿脚本只有 SREM 成功时才 INCR 库存。
补偿脚本重复执行不会把库存加多。
```

### 4.5 RabbitMQ 集成测试

用途：

```text
验证真实 RabbitMQ 拓扑、发布确认、消费 ACK、重试、死信和补偿。
```

建议技术：

```text
Testcontainers RabbitMQ
Spring AMQP
Awaitility
```

优先目标：

```text
FlashSaleOrderMqIT
FlashSaleOrderConsumerIT
```

重点断言：

```text
Producer 成功发布消息并获得 confirm ack。
Consumer 成功创建订单后 basicAck。
消息缺少必要字段进入 DLQ。
消费异常进入 retry queue。
超过重试次数进入 DLQ。
进入 DLQ 后触发 Redis 资格补偿。
Redis 资格不存在时消息按失效成功处理。
```

### 4.6 端到端主链路集成测试

用途：

```text
验证秒杀到支付闭环的关键路径。
数量少，但价值高。
```

建议只保留少量场景：

```text
FlashSalePaymentFlowIT
PaymentTimeoutFlowIT
```

重点链路：

```text
发布秒杀活动
  -> Redis 预热
  -> 用户秒杀下单
  -> RabbitMQ 异步创建订单
  -> 创建 Mock 支付单
  -> Mock webhook 支付成功
  -> 订单变为 PAID
  -> 支付单变为 PAID
  -> webhook 重复通知仍然成功且不重复更新
```

异步等待必须使用 Awaitility，不使用固定 `Thread.sleep`。

## 5. Maven 运行策略

建议保留默认 `mvn test` 只执行快速测试：

```bash
./mvnw test
```

建议新增 integration profile：

```bash
./mvnw verify -Pintegration
```

运行 integration profile 需要本机或 CI 有可用 Docker daemon。Testcontainers 集成测试不应在 Docker 缺失时静默降级为 mock 测试；如果 Docker 不可用，集成测试失败是合理信号。

推荐命名约定：

```text
src/test/java/**/*Test.java
src/test/java/**/*IT.java
```

推荐插件职责：

```text
maven-surefire-plugin  执行 *Test
maven-failsafe-plugin  执行 *IT
```

推荐依赖：

```text
spring-security-test
testcontainers-junit-jupiter
testcontainers-mysql
testcontainers-rabbitmq
awaitility
```

Redis Testcontainers 可以先使用 GenericContainer，不强依赖专门模块。

## 6. 测试基础设施

### 6.1 测试 profile

建议新增：

```text
src/test/resources/application-test.yaml
```

基础内容方向：

```yaml
spring:
  main:
    allow-bean-definition-overriding: true
  rabbitmq:
    listener:
      simple:
        auto-startup: false
app:
  payment:
    provider: MOCK
    order-expire-minutes: 15
    timeout-scan-fixed-delay-ms: 60000
```

集成测试中由 `@DynamicPropertySource` 注入：

```text
spring.datasource.url
spring.datasource.username
spring.datasource.password
spring.redis.host
spring.redis.port
spring.rabbitmq.host
spring.rabbitmq.port
spring.rabbitmq.username
spring.rabbitmq.password
```

### 6.2 集成测试基类

建议拆分基类，而不是所有集成测试共用一个巨大基类。

```text
AbstractMySqlIT
AbstractRedisIT
AbstractRabbitMqIT
AbstractFullStackIT
```

原则：

```text
只启动当前测试真正需要的容器。
避免所有 IT 都拉起 MySQL + Redis + RabbitMQ。
```

### 6.3 测试数据

建议测试数据在测试内显式创建，不依赖本地库已有数据。

可接受方式：

```text
@Sql
Mapper / Service 插入
测试工具类 TestDataFactory
```

不建议：

```text
依赖 application-local.yaml 指向的个人数据库。
依赖手工提前插入的数据。
多个测试共享可变业务数据。
```

## 7. 首批落地顺序

### Phase 1：测试骨架

目标：

```text
建立测试目录、依赖、profile、运行命令和命名约定。
```

任务：

```text
新增 spring-security-test。
新增 awaitility。
新增 Testcontainers 依赖。
配置 surefire / failsafe。
新增 application-test.yaml。
删除或改造空的 FlashSalePlatformApplicationTests。
```

验收：

```text
./mvnw test 可以稳定运行。
./mvnw verify -Pintegration 在无 IT 时也可稳定运行。
```

### Phase 2：支付和 webhook 快速测试

目标：

```text
先保护支付闭环最核心的状态判断和幂等行为。
```

任务：

```text
新增 PaymentServiceImplTest。
新增 PaymentWebhookServiceImplTest。
新增 PaymentControllerTest。
新增 PaymentWebhookControllerTest。
```

验收：

```text
支付创建主要失败分支和成功分支有断言。
webhook 重复事件和异常 payload 有断言。
Controller 未登录、登录、test profile webhook 行为有断言。
```

### Phase 3：Redis Lua 和 MySQL 真实约束

目标：

```text
开始引入真实基础设施，但范围收窄到 Redis 和 MySQL。
```

任务：

```text
新增 FlashSaleRedisLuaIT。
新增 RedisReservationCompensationIT。
新增 OrderPersistenceIT。
新增 PaymentWebhookPersistenceIT。
```

验收：

```text
Lua 返回码和 Redis 状态变化可被真实验证。
MySQL 唯一约束和事务行为可被真实验证。
```

### Phase 4：RabbitMQ 和端到端链路

目标：

```text
验证异步订单链路，不追求大量场景，只覆盖高风险路径。
```

任务：

```text
新增 FlashSaleOrderMqIT。
新增 FlashSaleOrderConsumerIT。
新增 FlashSalePaymentFlowIT。
```

验收：

```text
成功下单消息能被消费并创建订单。
失败消息能重试并进入 DLQ。
DLQ 能触发 Redis 资格补偿。
秒杀 -> 订单 -> 支付单 -> webhook 支付成功的主链路可跑通。
```

## 8. 首批测试用例矩阵

### 8.1 PaymentServiceImplTest

```text
createPayment_nullOrderId_returnsFailure
createPayment_orderNotFound_returnsFailure
createPayment_forbiddenOwner_returnsFailure
createPayment_invalidOrderStatus_returnsFailure
createPayment_expiredOrder_returnsFailure
createPayment_invalidAmount_returnsFailure
createPayment_invalidCurrency_returnsFailure
createPayment_existingPendingPayment_reusesPayment
createPayment_existingPaidPayment_reusesPayment
createPayment_existingCreatedPayment_returnsCreating
createPayment_unsupportedProvider_returnsFailure
createPayment_providerUnavailable_returnsFailure
createPayment_saveFailed_returnsFailure
createPayment_providerThrows_marksPaymentFailed
createPayment_success_returnsCheckoutInfo
queryPaymentStatus_forbiddenOwner_returnsFailure
queryPaymentStatus_success_returnsOrderAndPaymentStatus
```

### 8.2 PaymentWebhookServiceImplTest

```text
handleMockPaymentSucceeded_nullRequest_returnsFailure
handleMockPaymentSucceeded_missingEventId_returnsFailure
handleMockPaymentSucceeded_unsupportedEventType_returnsFailure
handleMockPaymentSucceeded_duplicateProcessedEvent_returnsOk
handleMockPaymentSucceeded_duplicateFailedEvent_returnsFailure
handleMockPaymentSucceeded_duplicateProcessingEvent_returnsFailure
handleMockPaymentSucceeded_paymentOrderNotFound_marksEventFailed
handleMockPaymentSucceeded_orderIdMismatch_marksEventFailed
handleMockPaymentSucceeded_amountMismatch_marksEventFailed
handleMockPaymentSucceeded_currencyMismatch_marksEventFailed
handleMockPaymentSucceeded_orderNotFound_marksEventFailed
handleMockPaymentSucceeded_alreadyPaid_isIdempotentSuccess
handleMockPaymentSucceeded_invalidOrderStatus_marksEventFailed
handleMockPaymentSucceeded_success_updatesOrderAndPayment
```

### 8.3 FlashSaleRedisLuaIT

```text
execute_whenOfferNotInitialized_returnsCode5
execute_whenNotStarted_returnsCode3
execute_whenEnded_returnsCode4
execute_whenStockAvailable_reservesAndReturnsCode0
execute_whenDuplicateUser_returnsCode2
execute_whenStockEmpty_returnsCode1
```

### 8.4 FlashSalePaymentFlowIT

```text
fullFlow_flashSaleToMockPaymentWebhook_success
fullFlow_duplicateWebhook_isIdempotent
fullFlow_invalidWebhook_doesNotMarkOrderPaid
```

## 9. 风险和取舍

### 9.1 Testcontainers 会增加运行时间

取舍：

```text
mvn test 不跑容器。
mvn verify -Pintegration 才跑容器。
```

这样日常开发不会被容器拖慢。

### 9.2 Spring Boot 2.3 与依赖版本需要谨慎

当前项目是 Spring Boot 2.3.12.RELEASE 和 Java 11。

依赖选择要避免引入只支持较新 Spring Boot 的测试库版本。

建议：

```text
Testcontainers 使用 1.19.x 或当前兼容 Java 11 的稳定版本。
Awaitility 使用 4.x。
spring-security-test 使用 Spring Boot 依赖管理版本。
```

### 9.3 当前代码可测试性可能需要小幅调整

部分 service 使用字段注入和 MyBatis-Plus 链式 API，单元测试时 mock 成本较高。

阶段性策略：

```text
先在测试中使用 ReflectionTestUtils 注入依赖。
遇到特别难测的链式查询，再考虑小范围抽取查询方法或改为构造器注入。
```

不建议为了测试一次性大规模重构 service。

### 9.4 集成测试数据库 schema 来源需要统一

当前已有：

```text
src/main/resources/db/flash_sale_platform.sql
```

后续需要确认：

```text
该 SQL 是否能在干净 MySQL 容器中一次性执行成功。
表名和实体映射是否完全一致。
唯一索引是否已经包含测试需要的约束。
```

如果不能，优先修正 schema 脚本，而不是在测试里手工绕过。

## 10. 完成定义

轻量生产级测试改造完成时，应满足：

```text
./mvnw test 稳定通过，适合日常开发频繁运行。
./mvnw verify -Pintegration 稳定通过，适合合并前验收。
支付和 webhook 核心状态分支有快速测试保护。
Redis Lua 使用真实 Redis 验证。
MySQL 唯一约束和事务使用真实 MySQL 验证。
RabbitMQ 重试、死信和补偿使用真实 RabbitMQ 验证。
端到端支付闭环至少有一条成功路径和两条异常路径。
测试不依赖 application-local.yaml 或个人本地数据。
```

## 11. 当前落地记录

### 2026-06-26

已落地 Phase 1：

```text
新增 surefire / failsafe 测试运行分层。
新增 integration profile。
新增 spring-security-test / awaitility / Testcontainers 依赖。
新增 src/test/resources/application-test.yaml。
移除空的默认 SpringBootTest。
```

已落地 Phase 2：

```text
PaymentServiceImplTest
PaymentWebhookServiceImplTest
OrderTimeoutServiceTest
RedisReservationCompensationServiceTest
PaymentControllerTest
PaymentWebhookControllerTest
```

已开始落地 Phase 3：

```text
FlashSaleRedisLuaIT
RedisReservationCompensationIT
OrderSchemaConstraintIT
PaymentOrderSchemaConstraintIT
PaymentWebhookSchemaConstraintIT
```

已开始落地 Phase 4：

```text
RabbitMqTopologyIT
FlashSaleOrderProducerIT
FlashSaleOrderConsumerTest
```

当前验证状态：

```text
./mvnw test
  通过，60 个快速测试全部成功。

./mvnw verify -Pintegration
  当前执行环境缺少 Docker，Testcontainers 无法启动容器。
  已新增的 Redis / MySQL / RabbitMQ 集成测试会在具备 Docker daemon 的本机或 CI 环境中执行。
```
