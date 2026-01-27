package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.lock.SimpleRedisLock;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

@Service
@Slf4j
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;


    // 代理对象（为了事务）
    private IVoucherOrderService proxy;

    // Lua 脚本对象
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));

    }

    // Redis快速校验 + 返回订单信息

    /**
     *
     * 请求线程只做Redis校验，成功就生成订单id返回
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1，获取用户id
        Long userId = UserHolder.getUser().getId();
        // 2. 调用lua 执行查询逻辑
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        log.info("seckill lua result={}, voucherId={}, userId={}", result, voucherId, userId);
        // 3. 判断查询结果，返回对应查询
        if (result == null || result.intValue() != 0) {
            int r = result == null ? -1 : result.intValue();
            switch (r) {
                case 1:
                    return Result.fail("库存不足");
                case 2:
                    return Result.fail("不能重复下单");
                case 3:
                    return Result.fail("库存数据异常，请联系管理员");
                default:
                    return Result.fail("系统繁忙，请稍后再试");
            }
        }

        // 4， 获取全局唯一id
        Long orderId = redisIdWorker.nextId("order");

        // 5. 封装卷订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 6. 订单入队
        orderTasks.add(voucherOrder);

        // 7. 获取代理对象，让事物生效
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }

    // 单一线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 消息队列
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 启动消费者线程
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 消费者线程类
    private  class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 将线程加入队列
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 执行购买流程
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单处理异常",e);
                }

            }
        }
    }

    // 通过redission避免并发创建，同一个用户的订单在异步线程里也不能并发创建。
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取id
        Long userId = voucherOrder.getUserId();
        // 2. 创建key
        RLock lock = redissonClient.getLock("order:" + userId);
        // 3. 查询
        boolean islock = lock.tryLock();
        if (!islock) {
            log.error("不允许重复下单");
            return ;
        }
        // 4. 使用代理，调用方法创建订单(为了能够使用事务)
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    // 真正落库，保证最终数据正确 （事务 + 一人一单 + 扣 DB 库存 + 保存订单）
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
     // 1. 获取用户id，订单id
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

     // 2. 一人一单（查询数据库保证最终一致性）
     int count = query()
             .eq("voucher_id",voucherId)
             .eq("user_id",userId)
             .count();
     if(count > 0){
         log.error("你已经抢过优惠卷啦");
         return;
     }

     // 3. 扣除库存
      boolean success = seckillVoucherService.update()
              .setSql("stock = stock -1")
              .eq("voucher_id",voucherId)
              .gt("stock",0)
              .update();

     if(!success){
         log.error("库存不足");
         return;
     }

     // 4. 保存订单
        save(voucherOrder);
    }


}
