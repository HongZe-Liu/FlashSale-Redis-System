package com.flashsale.payment.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.Order;
import com.flashsale.payment.mapper.OrderMapper;
import com.flashsale.payment.service.IFlashSaleOfferService;
import com.flashsale.payment.service.IOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.payment.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static com.flashsale.payment.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.flashsale.payment.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
@Slf4j
public class OrderServiceImpl
        extends ServiceImpl<OrderMapper, Order>
        implements IOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private IFlashSaleOfferService flashSaleOfferService;

    @Resource
    private IOrderService orderService;

    private static final String QUEUE_NAME = "stream.orders";
    private static final String DEAD_LETTER_QUEUE_NAME = "stream.orders.dlq";
    private static final String GROUP_NAME = "g1";
    private static final String CONSUMER_NAME = buildConsumerName();
    private static final String ORDER_LOCK_KEY = "lock:order:";
    private static final String RETRY_COUNT_KEY_PREFIX = "seckill:order:retry:";
    private static final int MAX_CONSUME_RETRY_COUNT = 3;
    private static final Duration PENDING_CHECK_INTERVAL = Duration.ofSeconds(5);
    private static final Duration RETRY_COUNT_TTL = Duration.ofDays(1);

    // Lua 脚本对象
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));

    }

    // Redis快速校验 + 返回订单信息

    @Override
    public Result placeFlashSaleOrder(Long offerId, Long userId){
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                offerId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        if (result == null) {
            return Result.fail("抢购失败，请稍后重试");
        }
        if (result.intValue() != 0){
            return Result.fail(flashSaleFailMessage(result.intValue()));
        }
        return Result.ok(orderId);
    }

    // 单一线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    // 创建消费组和线程
    @PostConstruct
    private void init() {
        initStreamGroup();
        // 线程
        SECKILL_ORDER_EXECUTOR.submit(new OrderHandler());
    }

    private void initStreamGroup() {
        try {
            Boolean exists = stringRedisTemplate.hasKey(QUEUE_NAME);
            if (Boolean.TRUE.equals(exists)) {
                stringRedisTemplate.opsForStream().createGroup(QUEUE_NAME, ReadOffset.from("0"), GROUP_NAME);
            } else {
                stringRedisTemplate.opsForStream().add(QUEUE_NAME, Collections.singletonMap("init", "1"));
                stringRedisTemplate.opsForStream().createGroup(QUEUE_NAME, ReadOffset.latest(), GROUP_NAME);
            }
        } catch (RedisSystemException e) {
            if (isBusyGroup(e)) {
                log.info("Redis Stream消费组已存在，queue={}, group={}", QUEUE_NAME, GROUP_NAME);
                return;
            }
            throw e;
        }
    }

    // 消费者线程类 -> 读取stream队列并入库
    private class OrderHandler implements Runnable {
        private long lastPendingCheckTime = System.currentTimeMillis();

        @Override
        public void run() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                            .read(Consumer.from(GROUP_NAME, CONSUMER_NAME),
                                    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                    StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                            );
                    if (records == null || records.isEmpty()) {
                        handlePendingListIfNecessary();
                        continue;
                    }
                    MapRecord<String, Object, Object> record = records.get(0);
                    if (!handleOrderRecord(record)) {
                        sleepBeforeRetry();
                        handlePendingList();
                    }
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingListIfNecessary() {
            long now = System.currentTimeMillis();
            if (now - lastPendingCheckTime < PENDING_CHECK_INTERVAL.toMillis()) {
                return;
            }
            lastPendingCheckTime = now;
            handlePendingList();
        }
    }

    // pending-list 逻辑
    private void handlePendingList() {
        while (true) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                        .read(Consumer.from(GROUP_NAME, CONSUMER_NAME),
                                StreamReadOptions.empty().count(1),
                                StreamOffset.create(QUEUE_NAME, ReadOffset.from("0")));
                if (records == null || records.isEmpty()) {
                    break;
                }

                MapRecord<String, Object, Object> record = records.get(0);
                if (!handleOrderRecord(record)) {
                    break;
                }
            } catch (Exception e) {
                log.info("pending-list 池里异常");
                sleepBeforeRetry();
            }
        }
    }

    private boolean handleOrderRecord(MapRecord<String, Object, Object> record) {
        Order order;
        try {
            Map<Object, Object> values = new HashMap<>(record.getValue());
            if (values.containsKey("voucherId") && !values.containsKey("offerId")) {
                values.put("offerId", values.get("voucherId"));
            }
            order = BeanUtil.fillBeanWithMap(values, new Order(), true);
        } catch (Exception e) {
            log.error("订单消息格式异常，将进入死信队列，messageId={}, values={}",
                    record.getId(), record.getValue(), e);
            writeDeadLetter(record, null, "message_convert_error", currentRetryCount(record.getId()));
            acknowledgeRecord(record);
            deleteRetryCount(record.getId());
            return true;
        }

        ConsumeDecision decision;
        try {
            decision = handleOrder(order);
        } catch (Exception e) {
            log.error("订单消息处理出现未预期异常，将保留消息等待重试，messageId={}, order={}",
                    record.getId(), order, e);
            decision = ConsumeDecision.retry("unexpected_consume_exception:" + e.getClass().getSimpleName());
        }

        if (decision.status == ConsumeStatus.SUCCESS) {
            acknowledgeRecord(record);
            deleteRetryCount(record.getId());
            return true;
        }

        if (decision.status == ConsumeStatus.DEAD_LETTER) {
            writeDeadLetter(record, order, decision.reason, currentRetryCount(record.getId()));
            acknowledgeRecord(record);
            deleteRetryCount(record.getId());
            return true;
        }

        return handleRetry(record, order, decision.reason);
    }

    // 通过redission避免并发创建，同一个用户的订单在异步线程里也不能并发创建。
    private ConsumeDecision handleOrder(Order order) {
        // 1. 获取id
        Long orderId = order.getId();
        Long userId = order.getUserId();
        Long offerId = order.getOfferId();
        if (orderId == null || userId == null || offerId == null) {
            log.error("订单消息缺少必要字段，order={}", order);
            return ConsumeDecision.deadLetter("missing_required_fields");
        }
        // 2. 创建key
        RLock lock = redissonClient.getLock(ORDER_LOCK_KEY + offerId + ":" + userId);
        // 3. 查询
        boolean islock;
        try {
            islock = lock.tryLock(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取用户下单锁被中断，userId={}, offerId={}", userId, offerId);
            return ConsumeDecision.retry("order_lock_interrupted");
        }
        if (!islock) {
            log.warn("获取用户下单锁失败，稍后重试，userId={}, offerId={}", userId, offerId);
            return ConsumeDecision.retry("order_lock_not_acquired");
        }
        // 4. 使用代理，调用方法创建订单(为了能够使用事务)
        try {
            boolean created = orderService.createOrder(order);
            if (!created) {
                compensateSoldOutConflict(order);
            }
            return ConsumeDecision.success();
        } catch (DuplicateKeyException e) {
            log.warn("订单唯一索引冲突，按幂等成功处理，userId={}, offerId={}, orderId={}",
                    userId, offerId, order.getId());
            return ConsumeDecision.success();
        } catch (Exception e) {
            if (isDuplicateOrderException(e)) {
                log.warn("订单唯一索引冲突，按幂等成功处理，userId={}, offerId={}, orderId={}",
                        userId, offerId, order.getId());
                return ConsumeDecision.success();
            }
            log.error("订单落库异常，将保留Stream消息等待重试，userId={}, offerId={}, orderId={}",
                    userId, offerId, order.getId(), e);
            return ConsumeDecision.retry("create_order_exception:" + e.getClass().getSimpleName());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 真正落库，保证最终数据正确 （事务 + 一人一单 + 扣 DB 库存 + 保存订单）
    @Transactional
    public boolean createOrder(Order order) {
        // 1. 获取用户id，订单id
        Long userId = order.getUserId();
        Long offerId = order.getOfferId();

        // 2. 一人一单（查询数据库保证最终一致性）
        int count = query()
                .eq("offer_id", offerId)
                .eq("user_id", userId)
                .count();
        if (count > 0) {
            log.warn("订单已存在，按幂等成功处理，userId={}, offerId={}", userId, offerId);
            return true;
        }

        // 3. 扣除库存
        boolean success = flashSaleOfferService.update()
                .setSql("stock = stock -1")
                .eq("offer_id", offerId)
                .gt("stock", 0)
                .update();

        if (!success) {
            log.warn("数据库库存扣减失败，将触发Redis预扣补偿，userId={}, offerId={}", userId, offerId);
            return false;
        }

        // 4. 保存订单
        boolean saved = save(order);
        if (!saved) {
            throw new IllegalStateException("保存秒杀订单失败");
        }
        return true;
    }

    private void compensateSoldOutConflict(Order order) {
        String offerId = order.getOfferId().toString();
        String userId = order.getUserId().toString();
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + offerId, "0");
        stringRedisTemplate.opsForSet().remove(SECKILL_ORDER_KEY + offerId, userId);
        log.warn("数据库库存扣减失败，已保守修正Redis库存并回滚用户资格，userId={}, offerId={}, orderId={}",
                userId, offerId, order.getId());
    }

    private String flashSaleFailMessage(int code) {
        switch (code) {
            case 1:
                return "库存不足";
            case 2:
                return "不能重复下单";
            case 3:
                return "秒杀活动尚未开始";
            case 4:
                return "秒杀活动已经结束";
            case 5:
                return "秒杀活动未初始化";
            default:
                return "抢购失败，请稍后重试";
        }
    }

    private boolean isBusyGroup(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("BUSYGROUP");
    }

    private static String buildConsumerName() {
        return "order-consumer-" + hostName() + "-" + UUID.randomUUID();
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private boolean isDuplicateOrderException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("idx_user_offer")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean handleRetry(MapRecord<String, Object, Object> record, Order order, String reason) {
        long retryCount = incrementRetryCount(record.getId());
        if (retryCount >= MAX_CONSUME_RETRY_COUNT) {
            log.error("订单消息消费失败超过重试上限，将进入死信队列，messageId={}, retryCount={}, reason={}, order={}",
                    record.getId(), retryCount, reason, order);
            writeDeadLetter(record, order, reason, retryCount);
            acknowledgeRecord(record);
            deleteRetryCount(record.getId());
            return true;
        }

        log.warn("订单消息消费失败，等待后续重试，messageId={}, retryCount={}, reason={}, order={}",
                record.getId(), retryCount, reason, order);
        return false;
    }

    private long incrementRetryCount(RecordId recordId) {
        String key = retryCountKey(recordId);
        Long retryCount = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, RETRY_COUNT_TTL.getSeconds(), TimeUnit.SECONDS);
        return retryCount == null ? 1 : retryCount;
    }

    private long currentRetryCount(RecordId recordId) {
        String retryCount = stringRedisTemplate.opsForValue().get(retryCountKey(recordId));
        if (retryCount == null) {
            return 0;
        }
        try {
            return Long.parseLong(retryCount);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void deleteRetryCount(RecordId recordId) {
        stringRedisTemplate.delete(retryCountKey(recordId));
    }

    private String retryCountKey(RecordId recordId) {
        return RETRY_COUNT_KEY_PREFIX + recordId.getValue();
    }

    private void acknowledgeRecord(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
    }

    private void writeDeadLetter(MapRecord<String, Object, Object> record, Order order,
                                 String reason, long retryCount) {
        String compensation = compensateDeadLetterReservation(record, order);
        Map<String, String> deadLetterMessage = new HashMap<>();
        deadLetterMessage.put("originalMessageId", record.getId().getValue());
        deadLetterMessage.put("reason", reason == null ? "unknown" : reason);
        deadLetterMessage.put("retryCount", String.valueOf(retryCount));
        deadLetterMessage.put("deadTime", String.valueOf(System.currentTimeMillis()));
        deadLetterMessage.put("id", orderValue(record, order, "id"));
        deadLetterMessage.put("offerId", orderValue(record, order, "offerId"));
        deadLetterMessage.put("userId", orderValue(record, order, "userId"));
        deadLetterMessage.put("compensation", compensation);
        stringRedisTemplate.opsForStream().add(DEAD_LETTER_QUEUE_NAME, deadLetterMessage);
        log.error("订单消息已写入死信队列，queue={}, messageId={}, reason={}, retryCount={}, compensation={}, order={}",
                DEAD_LETTER_QUEUE_NAME, record.getId(), reason, retryCount, compensation, order);
    }

    private String compensateDeadLetterReservation(MapRecord<String, Object, Object> record, Order order) {
        String offerId = orderValue(record, order, "offerId");
        String userId = orderValue(record, order, "userId");
        if (!hasText(offerId) || !hasText(userId)) {
            return "skipped_missing_required_fields";
        }
        try {
            stringRedisTemplate.opsForSet().remove(SECKILL_ORDER_KEY + offerId, userId);
            log.warn("死信订单已回滚Redis用户资格，userId={}, offerId={}, messageId={}",
                    userId, offerId, record.getId());
            return "user_reservation_rollback";
        } catch (Exception e) {
            log.error("死信订单回滚Redis用户资格失败，userId={}, offerId={}, messageId={}",
                    userId, offerId, record.getId(), e);
            return "user_reservation_rollback_failed";
        }
    }

    private String orderValue(MapRecord<String, Object, Object> record, Order order, String field) {
        if (order != null) {
            if ("id".equals(field) && order.getId() != null) {
                return order.getId().toString();
            }
            if ("offerId".equals(field) && order.getOfferId() != null) {
                return order.getOfferId().toString();
            }
            if ("userId".equals(field) && order.getUserId() != null) {
                return order.getUserId().toString();
            }
        }
        Object value = record.getValue().get(field);
        if (value == null && "offerId".equals(field)) {
            value = record.getValue().get("voucherId");
        }
        return value == null ? "" : value.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum ConsumeStatus {
        SUCCESS,
        RETRY,
        DEAD_LETTER
    }

    private static class ConsumeDecision {
        private final ConsumeStatus status;
        private final String reason;

        private ConsumeDecision(ConsumeStatus status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        private static ConsumeDecision success() {
            return new ConsumeDecision(ConsumeStatus.SUCCESS, null);
        }

        private static ConsumeDecision retry(String reason) {
            return new ConsumeDecision(ConsumeStatus.RETRY, reason);
        }

        private static ConsumeDecision deadLetter(String reason) {
            return new ConsumeDecision(ConsumeStatus.DEAD_LETTER, reason);
        }
    }

}
