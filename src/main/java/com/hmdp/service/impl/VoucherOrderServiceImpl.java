package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    String queueName = "stream.orders";

    // Lua 脚本对象
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));

    }

    // Redis快速校验 + 返回订单信息

    @Override
    public Result seckillVoucher(Long voucherId){
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().toString(),
                String.valueOf(orderId)
        );
        if (result.intValue() != 0){
            return  Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    // 单一线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    // 创建消费组和线程
    @PostConstruct
    private void init() {
        // stream group
        try {
            stringRedisTemplate.opsForStream().createGroup(
                    "stream.orders",
                    ReadOffset.from("0"),
                    "g1"
            );
        } catch (Exception e) {
        }
        // 线程
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 消费者线程类 -> 读取stream队列并入库
    private  class VoucherOrderHandler implements Runnable {
       @Override
        public void run(){
           while (true){
               try {
                   // 读取消息队列
                   List<MapRecord<String, Object,Object>> records = stringRedisTemplate.opsForStream()
                           .read(Consumer.from("g1","c1"),
                                   StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                   StreamOffset.create(queueName,ReadOffset.lastConsumed())
                           );
                   // 判断循环读取
                   if (records == null || records.isEmpty()) {
                       continue;
                   }
                   // 将获取的消息转化为对象
                   MapRecord<String,Object,Object> record = records.get(0);
                   Map<Object,Object> values = record.getValue();
                   // 封装到VoucherOrder , Map -> bean
                   VoucherOrder voucherOrder =
                           BeanUtil.fillBeanWithMap(values, new VoucherOrder(),true);
                   // 落库
                   handleVoucherOrder(voucherOrder);
                   // ack
                   stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
               }catch (Exception e){
                   log.error("订单处理异常",e);
                   // 异常则加入depending-list
                   handlePendingList();
               }

           }
       }
    }

    // pending-list 逻辑
    private void handlePendingList(){
        while (true){
            try {
                // 获取pending-list中的消息
                List<MapRecord<String,Object,Object>> records = stringRedisTemplate.opsForStream()
                        .read(Consumer.from("g1","c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName,ReadOffset.from("0")));
                // 判断
                if (records == null || records.isEmpty()) {
                    break;
                }

                // 转化为对象
                MapRecord<String,Object,Object> record = records.get(0);
                Map<Object,Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value,new VoucherOrder(),true);

                // 落库
                handleVoucherOrder(voucherOrder);
                // ack
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
            } catch (Exception e) {
                log.info("pending-list 池里异常");
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
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
