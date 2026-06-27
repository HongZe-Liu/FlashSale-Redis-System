# Lightweight Flash-Sale Load Test

这套压测脚本只覆盖最核心的秒杀链路：

```text
不同用户 token -> POST /flash-sales/{offerId}/orders
-> Redis Lua 准入
-> RabbitMQ 异步下单
-> MySQL 最终订单
```

它的目标不是先把工具学复杂，而是先跑出一条可验证、可复盘的压测闭环。

## 需要的工具

- Docker Compose：运行项目自带的 MySQL、Redis、RabbitMQ、应用。
- Node.js 18+：执行数据准备和结果校验脚本，不需要安装 npm 依赖。
- k6：执行 HTTP 压测脚本。

macOS 如果已经装了 Homebrew，可以这样安装 k6：

```bash
brew install k6
```

## 1. 启动应用

推荐使用 Docker Compose：

```bash
docker compose up -d --build
```

如果你没有 Docker，也可以使用本机 MySQL、Redis、RabbitMQ，然后用 local profile 启动应用：

```bash
JWT_SECRET=dev-only-change-me-dev-only-change-me-32bytes \
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

确认应用健康：

```bash
curl http://localhost:8080/actuator/health
```

## 2. 准备压测数据

先从小规模开始，便于理解结果：

```bash
USERS=200 STOCK=20 node load-tests/scripts/prepare-load-data.mjs
```

脚本默认会自动选择运行方式：如果有 `docker` 命令，就使用 Docker 容器；否则使用本机 `mysql` 和 `redis-cli`。也可以显式指定：

```bash
LOAD_TEST_INFRA=local USERS=200 STOCK=20 node load-tests/scripts/prepare-load-data.mjs
```

这个脚本会做四件事：

1. 创建一个专用秒杀商品，默认 `offerId=900001`。
2. 把该商品库存重置为 `STOCK`。
3. 创建 `USERS` 个压测用户，默认用户 ID 从 `1000000` 开始。
4. 为每个用户生成 JWT，并把登录态写入 Redis。

生成的 token 文件在：

```text
load-tests/out/tokens.json
```

常用参数：

```bash
USERS=5000 \
STOCK=100 \
OFFER_ID=900001 \
USER_ID_START=1000000 \
LOAD_TEST_INFRA=local \
JWT_SECRET=dev-only-change-me-dev-only-change-me-32bytes \
node load-tests/scripts/prepare-load-data.mjs
```

## 3. 执行下单压测

小规模试跑：

```bash
k6 run \
  -e BASE_URL=http://localhost:8080 \
  -e OFFER_ID=900001 \
  -e TOKEN_FILE=load-tests/out/tokens.json \
  -e VUS=50 \
  -e ITERATIONS=200 \
  load-tests/k6/flash-sale-order.js
```

正式一点的本机压测：

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

k6 会输出这些业务指标：

- `business_order_accepted`：Redis 准入成功，消息已发布到 RabbitMQ。
- `business_order_rejected_stock`：库存不足，这是秒杀售罄后的正常结果。
- `business_order_rejected_duplicate`：同一用户重复抢购。
- `business_order_rejected_other`：非预期业务失败，需要排查。

详细结果会写到：

```text
load-tests/out/k6-summary.json
```

## 4. 校验最终一致性

压测结束后执行：

```bash
node load-tests/scripts/verify-load-result.mjs
```

校验脚本会等待 RabbitMQ 消费完成，然后检查：

- MySQL 订单数是否等于 `min(STOCK, USERS)`。
- 是否存在同一用户重复订单。
- MySQL 库存是否正确扣减。
- Redis 库存和预约集合是否正确。
- RabbitMQ 创建队列和重试队列是否清空。
- DLQ 是否为 0。

如果你修改了规模，校验也可以显式传参：

```bash
USERS=5000 STOCK=100 EXPECTED_ACCEPTED=100 node load-tests/scripts/verify-load-result.mjs
```

本机服务模式下，校验脚本会通过 RabbitMQ Management API 读取队列状态。默认账号是 `guest/guest`，可以这样覆盖：

```bash
LOAD_TEST_INFRA=local \
RABBITMQ_MANAGEMENT_USERNAME=guest \
RABBITMQ_MANAGEMENT_PASSWORD=guest \
USERS=5000 STOCK=100 EXPECTED_ACCEPTED=100 \
node load-tests/scripts/verify-load-result.mjs
```

## 推荐学习顺序

1. 先跑 `USERS=200 STOCK=20`，理解准备数据、压测、校验三步。
2. 再跑 `USERS=5000 STOCK=100`，观察 Redis、RabbitMQ、MySQL 的变化。
3. 调大 `VUS`，看 HTTP p95/p99 延迟和 RabbitMQ 是否堆积。
4. 再去调应用配置，例如 Redis 连接池、数据库连接池、RabbitMQ consumer 并发。

## 注意

这套脚本会重置专用压测商品 `offerId=900001` 的订单和库存。不要把它直接指向真实生产库；如果要在预生产或压测环境使用，请使用独立数据库、独立 Redis、独立 RabbitMQ vhost，以及专用压测商品。
