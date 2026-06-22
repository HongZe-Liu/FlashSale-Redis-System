# 秒杀模块测试与压测方案

## 1. 测试目标

本测试方案用于证明秒杀模块的核心正确性：

- 不超卖。
- 不重复下单。
- 活动时间校验正确。
- Redis Stream 消费失败可治理。
- 重复消费幂等。
- Redis 预扣失败后有补偿闭环。
- Redis 秒杀缓存丢失后可以从 MySQL 重建。

## 2. 测试分层

建议分三层测试：

```text
Lua 脚本测试
  -> 验证 Redis 原子校验分支

Service 集成测试
  -> 验证 Redis + MySQL + Stream + 事务

压测/并发测试
  -> 验证库存不超卖、订单不重复、接口延迟和队列积压
```

## 3. 基础测试数据

建议准备一张秒杀券：

```text
voucherId = 1
stock = 10
beginTime < 当前时间
endTime > 当前时间
```

Redis 预热后应存在：

```text
seckill:stock:1
seckill:voucher:1
```

数据库表需要确保：

```sql
UNIQUE INDEX idx_user_voucher(user_id, voucher_id)
```

## 4. Lua 脚本测试用例

### 4.1 秒杀券未初始化

前置条件：

```text
删除 seckill:stock:{voucherId}
删除 seckill:voucher:{voucherId}
```

执行秒杀。

期望：

```text
返回：秒杀券未初始化
不写入 stream.orders
不扣库存
```

### 4.2 活动未开始

前置条件：

```text
begin > 当前时间
end > begin
```

期望：

```text
返回：秒杀活动尚未开始
不扣库存
不写入 stream.orders
```

### 4.3 活动已结束

前置条件：

```text
end < 当前时间
```

期望：

```text
返回：秒杀活动已经结束
不扣库存
不写入 stream.orders
```

### 4.4 库存不足

前置条件：

```text
seckill:stock:{voucherId} = 0
活动时间有效
```

期望：

```text
返回：库存不足
不写入 stream.orders
```

### 4.5 一人一单

前置条件：

```text
SADD seckill:order:{voucherId} {userId}
库存 > 0
活动时间有效
```

期望：

```text
返回：不能重复下单
库存不减少
不写入 stream.orders
```

### 4.6 正常入队

前置条件：

```text
库存 > 0
用户未抢过
活动时间有效
```

期望：

```text
接口返回 orderId
Redis 库存 -1
SADD seckill:order:{voucherId} userId
XADD stream.orders
相关 key 设置过期时间
```

## 5. 异步消费测试用例

### 5.1 正常消费创建订单

前置条件：

```text
stream.orders 中存在合法订单消息
MySQL 库存充足
用户没有订单
```

期望：

```text
tb_voucher_order 新增订单
tb_seckill_voucher stock -1
Stream 消息 ACK
Pending List 无积压
```

### 5.2 重复消费幂等

前置条件：

```text
数据库中已经存在 userId + voucherId 订单
再次投递同一用户同一券的订单消息
```

期望：

```text
不新增第二条订单
不重复扣 DB 库存
消息 ACK
```

### 5.3 数据库唯一索引冲突

前置条件：

```text
并发或手动制造 userId + voucherId 唯一索引冲突
```

期望：

```text
DuplicateKeyException 被识别为幂等成功
当前事务回滚本次库存扣减
消息 ACK
不进入 DLQ
```

### 5.4 获取锁失败

前置条件：

```text
提前占有 lock:order:{voucherId}:{userId}
消费对应订单消息
```

期望：

```text
消息不 ACK
retry count 增加
后续 Pending List 重试
未超过 3 次不进入 DLQ
```

### 5.5 消息字段缺失

前置条件：

```text
向 stream.orders 写入缺少 userId 或 voucherId 的消息
```

期望：

```text
消息进入 stream.orders.dlq
原消息 ACK
compensation = skipped_missing_required_fields
```

### 5.6 消费失败超过重试上限

前置条件：

```text
让订单落库持续失败
同一消息连续失败 3 次
```

期望：

```text
消息写入 stream.orders.dlq
原消息 ACK
retry key 被删除
如果 userId/voucherId 存在，回滚 Redis 用户资格
compensation = user_reservation_rollback
```

## 6. 失败补偿测试用例

### 6.1 MySQL 库存扣减失败

前置条件：

```text
Redis 库存 > 0
MySQL 库存 = 0
用户通过 Lua 预扣成功并入队
```

期望：

```text
MySQL 扣库存失败
seckill:stock:{voucherId} 设置为 0
SREM seckill:order:{voucherId} userId
消息 ACK
```

### 6.2 DLQ 前用户资格回滚

前置条件：

```text
Redis 中存在 seckill:order:{voucherId} userId
订单消息持续失败并进入 DLQ
```

期望：

```text
SREM seckill:order:{voucherId} userId
DLQ 中记录 compensation = user_reservation_rollback
```

## 7. 活动生命周期测试用例

### 7.1 创建秒杀券自动预热

执行：

```text
POST /voucher/seckill
```

期望：

```text
MySQL 保存 voucher 和 seckill_voucher
Redis 写入 stock key
Redis 写入 voucher hash
Redis key TTL = endTime + 1 天保留期
```

### 7.2 Redis 秒杀缓存重建

前置条件：

```text
删除 seckill:stock:{voucherId}
删除 seckill:voucher:{voucherId}
删除 seckill:order:{voucherId}
MySQL 中存在秒杀券和部分已下单用户
```

执行：

```text
调用 rebuildSeckillVoucherCache(voucherId)
```

期望：

```text
恢复 seckill:stock:{voucherId}
恢复 seckill:voucher:{voucherId}
恢复 seckill:order:{voucherId} 已下单用户集合
恢复 TTL
```

## 8. 并发与压测场景

### 8.1 并发不超卖

场景：

```text
库存 = 10
并发用户 = 100
每个用户请求一次
```

期望：

```text
成功订单数 <= 10
tb_seckill_voucher stock 不小于 0
tb_voucher_order 无重复 userId + voucherId
Redis 库存不小于 0
```

### 8.2 同一用户重复请求

场景：

```text
同一个 userId 并发请求 20 次
```

期望：

```text
最多一条订单
其余请求返回不能重复下单
```

### 8.3 Stream 积压观察

压测期间观察：

```text
XLEN stream.orders
XPENDING stream.orders g1
XLEN stream.orders.dlq
```

期望：

```text
压测结束后 stream.orders 不再持续增长
Pending List 可以被消费清空
DLQ 不应异常增长
```

## 9. 建议使用的工具

本地功能验证：

```text
Postman / Apifox
Redis CLI
MySQL 客户端
```

自动化测试：

```text
JUnit 5
SpringBootTest
Testcontainers MySQL
Testcontainers Redis
```

压测：

```text
JMeter
k6
wrk
```

## 10. 关键 Redis 检查命令

```text
GET seckill:stock:{voucherId}
HGETALL seckill:voucher:{voucherId}
SMEMBERS seckill:order:{voucherId}
XLEN stream.orders
XPENDING stream.orders g1
XRANGE stream.orders - +
XRANGE stream.orders.dlq - +
TTL seckill:stock:{voucherId}
TTL seckill:voucher:{voucherId}
TTL seckill:order:{voucherId}
```

## 11. 通过标准

秒杀模块测试通过标准：

```text
活动时间分支返回正确
库存不足分支返回正确
单用户不能重复下单
高并发下不超卖
重复消息不重复落库
唯一索引冲突按幂等成功处理
消费失败超过上限进入 DLQ
DLQ 前能回滚 Redis 用户资格
Redis 缓存丢失后可以从 MySQL 重建
活动结束后 Redis key 会自动过期
```

## 12. 当前限制

当前环境中没有 Maven 命令，也没有项目内 Maven Wrapper，因此本轮无法直接执行：

```text
mvn test
mvn package
```

后续在本机安装 Maven 或补充 Maven Wrapper 后，建议先执行：

```text
mvn test
mvn package -DskipTests
```
