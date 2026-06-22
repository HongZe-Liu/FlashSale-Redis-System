# 秒杀模块设计文档

## 1. 模块定位

本模块负责优惠券秒杀下单链路，核心目标是：

- 在高并发请求下避免超卖。
- 保证同一用户对同一张秒杀券只能下一单。
- 通过 Redis Stream 削峰，避免请求直接打满 MySQL。
- 对消费失败、重复消费、Redis 预扣失败等异常情况提供治理和补偿能力。

当前实现定位为：

> Redis 前置裁决 + Redis Stream 异步削峰 + MySQL 最终兜底。

## 2. 核心链路

```text
用户请求秒杀
  -> Controller 获取当前登录用户
  -> Service 生成订单 ID
  -> Lua 脚本在 Redis 中原子校验
       活动是否初始化
       是否在开始/结束时间内
       Redis 库存是否充足
       用户是否重复下单
       预扣 Redis 库存
       记录用户抢购资格
       写入 Redis Stream
  -> 接口快速返回订单 ID
  -> 后台消费者异步读取 Stream
  -> Redisson 按 voucherId + userId 加锁
  -> MySQL 事务中扣库存并创建订单
  -> 成功后 ACK 消息
```

相关文件：

- `src/main/java/com/hmdp/controller/VoucherOrderController.java`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `src/main/resources/seckill.lua`
- `src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java`
- `src/main/resources/db/hmdp.sql`

## 3. Redis Key 设计

```text
seckill:stock:{voucherId}
```

保存秒杀券 Redis 预扣库存。

```text
seckill:voucher:{voucherId}
```

Hash 结构，保存活动时间和保留时间：

```text
begin
end
retainSeconds
```

```text
seckill:order:{voucherId}
```

Set 结构，保存已经成功通过 Redis 预检的用户 ID，用于前置防重复下单。

```text
stream.orders
```

正常订单消息队列。

```text
stream.orders.dlq
```

死信队列，用于隔离多次消费失败或格式异常的订单消息。

```text
seckill:order:retry:{messageId}
```

记录 Stream 消息消费失败次数。

## 4. Lua 原子校验

Lua 脚本负责秒杀请求的前置判断和入队。

主要步骤：

1. 从 `seckill:voucher:{voucherId}` 读取活动开始时间、结束时间和保留时间。
2. 使用 Redis `TIME` 判断当前是否在活动时间内。
3. 从 `seckill:stock:{voucherId}` 判断库存是否充足。
4. 使用 `SISMEMBER seckill:order:{voucherId} userId` 判断用户是否重复抢购。
5. 使用 `INCRBY stockKey -1` 预扣库存。
6. 使用 `SADD orderKey userId` 记录用户抢购资格。
7. 使用 `XADD stream.orders` 写入异步下单消息。
8. 使用 `EXPIREAT` 为秒杀相关 key 设置活动结束后的自动清理时间。

Lua 返回码：

```text
0 成功入队
1 库存不足
2 不能重复下单
3 秒杀活动尚未开始
4 秒杀活动已经结束
5 秒杀券未初始化
```

## 5. 异步消费与削峰

秒杀接口不直接写 MySQL，而是通过 Redis Stream 将订单创建异步化。

当前消费者模型：

```text
stream.orders
group: g1
consumer: order-consumer-{hostname}-{uuid}
```

消费者读取消息后：

1. 将消息转换成 `VoucherOrder`。
2. 校验 `id / voucherId / userId` 是否完整。
3. 使用 Redisson 锁 `lock:order:{voucherId}:{userId}` 防止并发重复创建。
4. 调用事务方法创建订单。
5. 成功后 ACK Stream 消息。

## 6. 消费治理

消费者将处理结果拆成三类：

```text
SUCCESS
RETRY
DEAD_LETTER
```

处理语义：

```text
SUCCESS
  -> ACK 消息
  -> 删除 retry key

RETRY
  -> 增加 retry count
  -> 未超过上限则不 ACK，等待 Pending List 重试
  -> 超过上限写入 DLQ 并 ACK

DEAD_LETTER
  -> 写入 stream.orders.dlq
  -> ACK 原消息
  -> 删除 retry key
```

当前最大重试次数：

```text
3 次
```

DLQ 消息字段：

```text
originalMessageId
id
voucherId
userId
reason
retryCount
deadTime
compensation
```

## 7. 幂等设计

本模块有多层防重复设计：

```text
Redis Set 前置判断
  -> Lua 中使用 seckill:order:{voucherId} 判断用户是否抢过

Redisson 分布式锁
  -> 消费端按 voucherId + userId 加锁

业务 count 查询
  -> 落库前检查订单是否已经存在

数据库唯一索引
  -> tb_voucher_order(user_id, voucher_id) 唯一约束作为最终兜底
```

数据库唯一索引：

```sql
UNIQUE INDEX idx_user_voucher(user_id, voucher_id)
```

如果消费端遇到唯一索引冲突：

```text
说明订单很可能已经创建成功
当前消息按幂等成功处理
ACK 消息
不进入无意义重试
```

## 8. 失败补偿

### 8.1 数据库库存扣减失败

如果 Redis 已经预扣成功，但 MySQL 库存扣减失败，说明 Redis 库存和 MySQL 最终库存存在不一致风险。

当前采取保守补偿：

```text
seckill:stock:{voucherId} 设置为 0
SREM seckill:order:{voucherId} userId
ACK 消息
```

原因：

> MySQL 已经扣不动库存时，不盲目回补 Redis 库存，避免继续放请求进入秒杀链路。

### 8.2 消息最终失败进入 DLQ

如果消息重试超过上限进入死信队列，并且能识别出 `voucherId` 和 `userId`，会执行：

```text
SREM seckill:order:{voucherId} userId
```

作用：

> 避免数据库没有订单，但 Redis 长期误判用户已经下单。

补偿结果会写入 DLQ 的 `compensation` 字段：

```text
user_reservation_rollback
skipped_missing_required_fields
user_reservation_rollback_failed
```

## 9. 活动生命周期治理

新增秒杀券时会预热 Redis：

```text
seckill:stock:{voucherId}
seckill:voucher:{voucherId}
```

同时设置 TTL：

```text
活动结束时间 + 1 天保留期
```

保留期用于：

- 异步订单消费。
- 失败排查。
- 补偿处理。

Redis 数据丢失后，可以通过 `rebuildSeckillVoucherCache(Long voucherId)` 从 MySQL 重建：

```text
tb_seckill_voucher
  -> stock / beginTime / endTime

tb_voucher_order
  -> 已下单 userId 集合
```

重建后恢复：

```text
seckill:stock:{voucherId}
seckill:voucher:{voucherId}
seckill:order:{voucherId}
```

## 10. 消费者扩容模型

当前实现：

```text
单实例
单线程消费者
Redis Stream Consumer Group
```

当前选择单线程的原因：

- 实现简单，方便验证链路正确性。
- 秒杀入口已经由 Redis Lua 完成削峰。
- 订单落库链路需要事务、锁、幂等和补偿，先保证正确性优先。

后续扩容路线：

```text
单实例多消费者线程
  -> 同一个 group，不同 consumerName

多服务实例
  -> 同一个 stream.orders
  -> 同一个 group g1
  -> 每个实例生成唯一 consumerName

宕机消息接管
  -> 使用 XPENDING / XCLAIM / XAUTOCLAIM 接管其他消费者遗留的 Pending 消息
```

当前已经支持每个实例生成唯一消费者名：

```text
order-consumer-{hostname}-{uuid}
```

## 11. 当前边界

当前模块已经具备比较完整的工程骨架，但仍有生产级边界：

- 未实现跨消费者 Pending 消息自动接管。
- 未接入 Prometheus/Grafana 等指标监控。
- 未实现后台管理端的秒杀活动修改同步接口。
- 未实现完整的自动化集成测试和压测报告。
- Redis 高可用仍依赖外部基础设施，如 Sentinel、Cluster、AOF/RDB 配置。

## 12. 简历表达

可以写成：

> 实现基于 Redis Lua + Redis Stream 的优惠券秒杀下单链路。通过 Lua 脚本完成活动时间、库存和一人一单的原子校验，并将订单消息写入 Stream 异步消费；消费端支持 Pending List 重试、重试次数上限、死信队列、幂等落库、数据库唯一索引兜底和 Redis 资格回滚补偿；同时支持秒杀活动 Redis 缓存预热、过期清理和从 MySQL 重建。
