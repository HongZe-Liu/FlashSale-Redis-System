# Phase 4 Observability Plan

## 1. 阶段四定位

阶段四目标是为当前秒杀交易系统补齐生产对齐的轻量级可观测性能力。

本阶段不是做一个玩具级 `/metrics` JSON 接口，而是采用 Spring Boot 生产项目中常见的技术路线：

```text
Spring Boot Actuator
  -> Micrometer
  -> Prometheus metrics endpoint
  -> Prometheus scrape
  -> Grafana dashboard
```

第一版重点是建立可观测性骨架，让核心交易链路“看得见、能定位、能解释”。

核心链路包括：

```text
登录
验证码
秒杀请求
Redis Lua 裁决
RabbitMQ 投递
RabbitMQ 消费
订单创建
支付单创建
支付 webhook
超时关单
库存补偿
```

## 2. 第一版范围

### 本阶段第一版要做

- 接入 Spring Boot Actuator。
- 接入 Micrometer Prometheus registry。
- 暴露标准 Prometheus 指标端点。
- 新增统一业务指标组件 `BusinessMetrics`。
- 为核心交易链路补业务 Counter。
- 为少量关键耗时链路预留 Timer 能力。
- 规范关键异常日志字段。
- 新增本地 Prometheus 配置。
- README 增加可观测性说明。
- 增加阶段四验收说明。

### 第一版先不做

- 不做 OpenTelemetry 分布式链路追踪。
- 不做 Jaeger / Tempo。
- 不做 ELK / Loki 集中日志。
- 不做完整 Grafana dashboard JSON。
- 不做 Prometheus alert rules。
- 不做 Kubernetes ServiceMonitor。
- 不接 RabbitMQ exporter。
- 不做多实例生产部署配置。

这些能力属于生产增强项，不作为 Demo 第一版必须完成内容。

## 3. 为什么这不是玩具方案

本阶段使用的是生产项目常见的标准组件，而不是自定义临时方案。

生产对齐点：

```text
Actuator 提供标准健康检查和指标入口。
Micrometer 作为 Java 应用指标门面。
Prometheus 通过 /actuator/prometheus 拉取指标。
Grafana 负责指标可视化。
业务指标围绕真实交易链路设计。
具体业务 id 进入日志，不进入 metrics label。
Actuator 在生产环境需要限制暴露范围和访问权限。
```

Demo 简化点：

```text
本地直接暴露 actuator endpoint。
Prometheus 使用本地配置抓取单实例应用。
Grafana dashboard 可以先用文档说明，不强制提交完整 JSON。
日志先使用结构化字段风格，不接集中日志系统。
```

## 4. 核心概念边界

### Metrics

Metrics 是可以被聚合和绘图的数字。

适合回答：

```text
过去 5 分钟秒杀成功多少次？
webhook 失败次数是否变多？
MQ 投递失败是否出现尖峰？
订单超时取消有多少？
```

### Logs

Logs 是具体事件记录。

适合回答：

```text
是哪一个 orderId 失败？
是哪一个 eventId 重复？
失败原因是什么？
```

### Traces

Traces 用于跨服务链路追踪。

当前项目是单体应用，第一版暂不接 OpenTelemetry。后续如果拆成多个服务，再考虑 traces。

## 5. 指标设计原则

### 使用低基数 tag

可以作为 metrics tag：

```text
result=success|failure|noop
reason=stock_not_enough|duplicate_order|not_started|ended|not_initialized
destination=order_create|retry|dead_letter
provider=MOCK|STRIPE
status=paid|failed|expired
```

不要作为 metrics tag：

```text
userId
orderId
offerId
paymentOrderId
providerPaymentId
eventId
email
phone
```

原因是这些字段基数太高，会导致 Prometheus 时间序列爆炸。

### 业务 id 进入日志

这些字段应该进入日志：

```text
userId
orderId
offerId
paymentOrderId
providerPaymentId
eventId
reason
```

### 指标命名保持统一

统一使用项目前缀：

```text
flashsale.*
```

指标名称表达业务动作：

```text
flashsale.payment.webhook.received
flashsale.payment.webhook.duplicate
flashsale.order.timeout.expired
flashsale.compensation.redis.success
```

注意：Micrometer 中使用点号命名，Prometheus 暴露时通常会转换为下划线并追加类型后缀。

示例：

```text
flashsale.payment.webhook.success
-> flashsale_payment_webhook_success_total
```

验收时应以 `/actuator/prometheus` 中实际暴露名称为准，不要求 Prometheus 输出保留点号。

## 6. 技术指标

Actuator + Micrometer 会自动暴露一批技术指标。

第一版重点观察：

```text
HTTP request count
HTTP request latency
HTTP 4xx / 5xx
JVM memory
JVM GC
JVM threads
Tomcat threads
HikariCP database connections
```

这些指标不需要大量手写代码，主要通过依赖和配置开启。

## 7. 业务指标

第一版业务指标以 Counter 为主。

### Auth

```text
flashsale.auth.login.success
flashsale.auth.login.failure
flashsale.auth.code.sent
flashsale.auth.code.rate_limited
```

### Flash Sale

```text
flashsale.order.request
flashsale.order.request.success
flashsale.order.request.failure
```

当前项目中，秒杀请求成功表示 Redis Lua 裁决成功并且 RabbitMQ 初始订单消息投递成功。

它不等同于订单已经落库成功。订单真实创建结果由消费端指标 `flashsale.order.create.*` 表达。

建议 failure 使用低基数 reason：

```text
stock_not_enough
duplicate_order
not_started
ended
not_initialized
unknown
```

### RabbitMQ

```text
flashsale.mq.publish.success
flashsale.mq.publish.failure
flashsale.mq.consume.success
flashsale.mq.consume.failure
flashsale.mq.dead_letter
```

MQ publish 建议增加低基数 `destination` tag，用于区分投递目标：

```text
destination=order_create
destination=retry
destination=dead_letter
```

`flashsale.mq.consume.success` 表示消息被消费者成功处理并 ACK。

如果消费过程中发现订单无法创建但已经完成补偿并 ACK，应同时记录：

```text
flashsale.mq.consume.success
flashsale.order.create.failure{reason="db_stock_not_enough"}
flashsale.compensation.redis.*
```

不要把这类场景只归类为 MQ 消费失败，否则会混淆“消息处理失败”和“业务落库失败”。

### Order

```text
flashsale.order.create.success
flashsale.order.create.idempotent
flashsale.order.create.failure
flashsale.order.timeout.expired
```

`flashsale.order.create.success` 表示消费端真实新建订单成功。

如果消费重试或重复消息发现订单已存在，应记录 `flashsale.order.create.idempotent`，不要计入普通 create success。

### Payment

```text
flashsale.payment.create.success
flashsale.payment.create.reused
flashsale.payment.create.failure
flashsale.payment.webhook.received
flashsale.payment.webhook.duplicate
flashsale.payment.webhook.success
flashsale.payment.webhook.failure
```

`flashsale.payment.create.success` 表示新支付单创建并成功拿到支付渠道结果。

如果请求命中已有可复用支付单，应记录 `flashsale.payment.create.reused`，不要计入普通 create success，避免幂等重试放大成功创建数。

Payment create 指标建议增加低基数 `provider` tag：

```text
provider=mock
provider=stripe
provider=unknown
```

webhook duplicate 单独记录 `flashsale.payment.webhook.duplicate`。

重复 webhook 即使按幂等成功返回，也不要同时记录 `flashsale.payment.webhook.success`，否则支付渠道重试会放大 webhook 成功数。

### Compensation

```text
flashsale.compensation.redis.success
flashsale.compensation.redis.failure
flashsale.compensation.redis.noop
```

Redis 补偿结果需要区分：

```text
success: 确实回滚了 Redis 库存和用户资格。
failure: 脚本执行异常、返回空、参数缺失等需要关注的问题。
noop: 用户资格已经不存在、无需补偿等非错误场景。
```

## 8. 建议新增组件

### BusinessMetrics

新增统一业务指标组件：

```text
com.flashsale.payment.observability.BusinessMetrics
```

职责：

```text
封装 MeterRegistry
统一指标名称
统一 tag 枚举值
避免各个 service 直接散落拼接指标名
避免误把高基数字段放进 tag
```

第一版可以提供方法：

```text
recordLoginSuccess()
recordLoginFailure(String reason)
recordFlashSaleRequestSuccess()
recordFlashSaleRequestFailure(String reason)
recordMqPublishSuccess()
recordMqPublishFailure(String reason)
recordMqConsumeSuccess()
recordMqConsumeFailure(String reason)
recordMqDeadLetter(String reason)
recordOrderCreateSuccess()
recordOrderCreateIdempotent()
recordOrderCreateFailure(String reason)
recordOrderTimeoutExpired()
recordPaymentCreateSuccess(String provider)
recordPaymentCreateReused(String provider)
recordPaymentCreateFailure(String provider, String reason)
recordPaymentWebhookReceived(String provider)
recordPaymentWebhookDuplicate(String provider, String status)
recordPaymentWebhookSuccess(String provider)
recordPaymentWebhookFailure(String provider, String reason)
recordRedisCompensationSuccess(String reason)
recordRedisCompensationFailure(String reason)
recordRedisCompensationNoop(String reason)
```

### ObservabilityProperties

可选。

第一版如果指标不需要动态开关，可以暂不新增配置类。

## 9. 日志规范

指标用于看趋势，日志用于查具体事件。

关键日志建议包含：

```text
orderId
offerId
userId
paymentOrderId
provider
providerPaymentId
eventId
reason
```

示例：

```text
flash sale order rejected, offerId=1, userId=2, reason=stock_not_enough
mq publish failed, orderId=123, offerId=1, userId=2, reason=confirm_timeout
order create failed, orderId=123, offerId=1, userId=2, reason=db_stock_not_enough
payment webhook failed, eventId=evt_001, orderId=123, provider=MOCK, reason=amount_mismatch
order timeout expired, orderId=123, offerId=1, userId=2
redis compensation success, orderId=123, offerId=1, userId=2, reason=mq_publish_failed
```

第一版继续使用当前日志框架即可，不引入 JSON log encoder。

## 10. Actuator 暴露策略

### local / demo

本地 Demo 可以开放：

```text
/actuator/health
/actuator/info
/actuator/metrics
/actuator/prometheus
```

当前项目已有 Spring Security，并且默认要求认证。

第一版需要在 local / demo 场景放行上述 actuator endpoint，否则 `/actuator/health` 和 `/actuator/prometheus` 会被认证链路返回 401。

### production

生产环境不能裸露全部 actuator endpoint。

生产建议：

```text
只暴露 health 和 prometheus。
放在内网或管理端口。
通过网关、防火墙或 Spring Security 限制访问。
不要暴露 env、beans、configprops 等敏感 endpoint。
```

第一版只在 README 中说明生产保护策略，不强行实现完整生产鉴权。

## 11. Prometheus 本地配置

建议新增：

```text
infra/prometheus/prometheus.yml
```

本地配置示例：

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: "flash-sale-payment"
    metrics_path: "/actuator/prometheus"
    static_configs:
      - targets: ["host.docker.internal:8080"]
```

如果 Prometheus 不跑在 Docker 中，可以使用：

```text
localhost:8080
```

## 12. Grafana 第一版边界

第一版不强制提交 dashboard JSON。

推荐 dashboard 分组：

```text
HTTP throughput and latency
Flash sale success and failure
RabbitMQ publish / consume / dead-letter
Order create and timeout
Payment create and webhook result
Redis compensation
JVM and database pool
```

后续如果要增强，可以新增：

```text
infra/grafana/dashboards/flash-sale-payment.json
```

## 13. 分阶段实施计划

### 4.1 接入 Actuator 和 Prometheus

任务：

```text
新增 spring-boot-starter-actuator
新增 micrometer-registry-prometheus
配置 management.endpoints.web.exposure.include
配置 /actuator/prometheus
调整 Spring Security local / demo 白名单，允许访问 health / info / metrics / prometheus
README 增加本地访问说明
```

验收：

```text
依赖服务 MySQL / Redis / RabbitMQ 正常时，GET /actuator/health returns UP
如果依赖服务未启动，health 可能返回 DOWN，应能从 health detail 定位依赖问题
GET /actuator/prometheus returns Prometheus format metrics
可以看到 jvm、http、tomcat、hikaricp 等技术指标
```

### 4.2 新增 BusinessMetrics

任务：

```text
新增 observability 包
新增 BusinessMetrics
封装 Counter 记录方法
统一 reason 和 provider tag
统一 destination / result 等低基数 tag
避免高基数字段进入 tag
```

验收：

```text
代码中有统一指标入口
service 不直接散落复杂 MeterRegistry 调用
业务指标名称具有统一前缀 flashsale.*
```

### 4.3 核心链路埋点

任务：

```text
登录成功 / 失败
验证码发送 / 限流
秒杀请求成功 / 失败原因
MQ 投递成功 / 失败
MQ 消费成功 / 失败 / 死信
订单创建成功 / 失败
订单超时取消
支付创建成功 / 复用 / 失败
webhook 接收 / 成功 / 失败 / 重复
Redis 补偿成功 / 失败 / 无需补偿
```

验收：

```text
执行本地接口验收后，/actuator/prometheus 能看到对应业务指标递增
异常场景能看到对应 failure counter 递增
幂等复用、重复 webhook、无需补偿等场景不污染普通 success / failure 口径
```

### 4.4 日志收口

任务：

```text
补齐关键失败日志字段
统一 reason 命名
确保日志包含排障所需业务 id
```

验收：

```text
MQ 投递失败可以从日志定位 orderId / offerId / userId / reason
webhook 失败可以从日志定位 eventId / orderId / provider / reason
补偿执行可以从日志定位 orderId / offerId / userId / reason
```

### 4.5 Prometheus 和文档

任务：

```text
新增 infra/prometheus/prometheus.yml
新增 docs/observability-acceptance-test.md
README 增加 Observability 章节
说明 Demo 与生产环境差异
```

验收：

```text
Prometheus 可以抓取 /actuator/prometheus
README 能说明如何查看 health 和 metrics
docs 能说明如何验证业务指标
```

## 14. 阶段四完成标准

阶段四第一版完成后，应满足：

```text
/actuator/health 可访问。
/actuator/prometheus 可访问。
Prometheus 可以抓取应用指标。
应用自动暴露 HTTP、JVM、Tomcat、HikariCP 等技术指标。
登录、验证码、秒杀、MQ、订单、支付、webhook、补偿有业务指标。
业务指标不使用 userId / orderId / eventId 等高基数字段作为 tag。
关键异常日志能通过 orderId / eventId / reason 定位问题。
README 能讲清本地如何观察指标。
文档能讲清 Demo 与生产环境的差异。
```

## 15. 后续增强方向

阶段四第一版完成后，可以按需要增强：

```text
Grafana dashboard JSON
Prometheus alert rules
RabbitMQ exporter
Redis exporter
MySQL exporter
OpenTelemetry tracing
Loki / ELK centralized logging
Kubernetes ServiceMonitor
Actuator management port isolation
```

这些属于生产深化方向，不影响第一版 Demo 完成。

## 16. 学习拆解顺序

实现完成后，建议按以下顺序回头学习：

```text
1. /actuator/health：应用健康检查是什么
2. /actuator/prometheus：指标出口是什么
3. Micrometer：Counter / Timer / Gauge / tag
4. Prometheus：scrape / job / target / time series
5. 业务指标：如何观察秒杀、MQ、支付、补偿
6. 高基数问题：为什么 orderId 不能进入 tag
7. Grafana：如何把 Prometheus 数据画出来
8. 生产差异：endpoint 安全、告警、集中日志、链路追踪
```

这份文档是阶段四代码改造的锚点。后续实现时以本计划为准，避免一次性扩展到过重的监控平台。
