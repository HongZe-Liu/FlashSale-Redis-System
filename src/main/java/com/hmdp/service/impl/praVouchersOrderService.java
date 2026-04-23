//package com.hmdp.service.impl;
//
//import cn.hutool.core.bean.BeanUtil;
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import com.hmdp.dto.Result;
//import com.hmdp.entity.VoucherOrder;
//import com.hmdp.mapper.VoucherMapper;
//import com.hmdp.mapper.VoucherOrderMapper;
//import com.hmdp.service.ISeckillVoucherService;
//import com.hmdp.service.IVoucherOrderService;
//import com.hmdp.utils.RedisIdWorker;
//import com.hmdp.utils.UserHolder;
//import lombok.extern.slf4j.Slf4j;
//import org.redisson.api.RLock;
//import org.redisson.api.RedissonClient;
//import org.springframework.aop.framework.AopContext;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.context.annotation.Bean;
//import org.springframework.core.io.ClassPathResource;
//import org.springframework.data.redis.connection.stream.*;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.data.redis.core.script.DefaultRedisScript;
//import org.springframework.security.core.parameters.P;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import javax.annotation.PostConstruct;
//import javax.annotation.Resource;
//import java.time.Duration;
//import java.util.Collections;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//
//@Service
//@Slf4j
//
//public class praVouchersOrderService
//            extends ServiceImpl<VoucherOrderMapper,VoucherOrder>
//            implements IVoucherOrderService{
//
//    @Resource
//    private StringRedisTemplate stringRedisTemplate;
//    @Resource
//    private  IVoucherOrderService voucherOrderService;
//    @Resource
//    private ISeckillVoucherService seckillVoucherService;
//    @Autowired
//    private RedisIdWorker redisIdWorker;
//    @Autowired
//    private RedissonClient redissonClient;
//
//    private static final String QUEUE_NAME = "stream.orders";
//    private static final String GROUP = "g1";
//    private static final String CONSUMER = "c1";
//    // 1. 封装Lua 脚本封装成一个 Java 对象
//    // 1.1  创建成员变量
//    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
//    // 1.2  static 初始化成员变量
//    static {
//        SECKILL_SCRIPT = new DefaultRedisScript<>();
//        SECKILL_SCRIPT.setResultType(Long.class);
//        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
//
//    }
//
//    // 2. 创建单一线程池:按照顺序执行Stream 消息队列
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR =
//            Executors.newSingleThreadExecutor();
//
//
//    // 3. 创建消费者 + 启动消费线程
//    @PostConstruct
//    private void init(){
//        // 3.1 创建消费者组
//        try {
//            stringRedisTemplate.opsForStream().createGroup(
//                    QUEUE_NAME,
//                    ReadOffset.from("0"),
//                    GROUP
//            );
//            log.info("创建消费者组成功, stream={}, group={}", QUEUE_NAME, GROUP);
//        } catch (Exception e) {
//            String msg = e.getMessage();
//            if (msg != null && msg.contains("BUSYGROUP")){
//                // 组已经存在: 正常情况无需外抛退出
//                log.info("消费者组已存在, stream={}, group={}", QUEUE_NAME, GROUP);
//            }else{
//                // 其他类型的异常则需要暴露,让应用启动失败
//                log.error("创建消费者组失败, stream={}, group={}", QUEUE_NAME, GROUP, e);
//                throw e;
//            }
//        }
//        // 3.2 启动消费者线程
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHolder());
//
//    }
//
//    // 4. 利用lua 脚本快速检验 + 返回订单id（真正落库由异步线程完成）
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 4.1 创建订单全局唯一id : 用于异步消息传递 + 最终数据库保存,同时立即返回给前端
//        long orderId = redisIdWorker.nextId("order");
//        // 4.2 调用Lua脚本快速redis校验
//            // execute(脚本名称,key,argv[1].argv[2],argv[3])
//            // lua 脚本返回值: 0(成功)1(库存不足)2(重复下单)
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(), // 因为自定义为key,因此传递一个空list
//                voucherId.toString(), // 优惠卷id
//                UserHolder.getUser().getId().toString(), // 获取用户名
//                String.valueOf(orderId) // 订单id
//        );
//        if(result == null || result.intValue() != 0){
//            return Result.fail(result != null && result.intValue() == 1? "库存不足" : "不能重复下单");
//
//        }
//        return Result.ok(orderId);
//    }
//
//    // 5. 创建消费者线程类: 读取stream并入库
//    private class VoucherOrderHolder implements Runnable{
//        @Override
//        public void run(){
//            while(true){
//                MapRecord<String, Object, Object> record = null;
//                Map<Object, Object> value = null;
//                VoucherOrder voucherOrder = null;
//                // 5.1 读取队列消息
//                // MapRecord<Streamkey, 消息id, 消息体>
//                try {
//                    List<MapRecord<String,Object,Object>> records =
//                            stringRedisTemplate.opsForStream().read(
//                                    Consumer.from(GROUP,CONSUMER),
//                                    // 设置读取选项:count(每次读取几条).block(如果没消息堵塞几秒)
//                                    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                                    // 用StreamOffset.create 用于指定读取哪个 Stream 以及从哪个偏移量开始读取。
//                                    // 在消费者组模式下，如果使用 ReadOffset.lastConsumed()，则表示从消费者组维护的最后消费位置之后读取尚未分配的新消息。
//                                    StreamOffset.create(QUEUE_NAME,ReadOffset.lastConsumed())
//                            );
//                    // 5.2 判断消息队列是否有消息,无消息则跳过当前循环
//                    if(records == null || records.isEmpty()){
//                        continue;
//                    }
//
//                    // 5.3 读取record list 中的消息,从第一条(下标o开始)
//                    record = records.get(0);
//                    value = record.getValue();
//                    voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
//                    log.debug("消费到消息, recordId={}, value={}", record.getId(), value);
//
//                    handleVoucherOrder(voucherOrder);
//
//                    // ack 确认
//                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME,GROUP,record.getId());
//                    log.debug("处理成功并ack, recordId={}, orderId={}, userId={}, voucherId={}",
//                            record.getId(),
//                            voucherOrder.getId(),
//                            voucherOrder.getUserId(),
//                            voucherOrder.getVoucherId()
//                    );
//                } catch (Exception e) {
//                    log.error("订单处理异常, recordId={},orderId={}, userId={}, voucherId={}, value={}",
//                            record == null ? null : record.getId(),
//                            voucherOrder == null ? null : voucherOrder.getId(),
//                            voucherOrder == null ? null : voucherOrder.getUserId(),
//                            voucherOrder == null ? null : voucherOrder.getVoucherId(),
//                            value,
//                            e
//                    );
//                    // 异常则进入pending-list
//                    handPendingList();
//                }
//            }
//        }
//    }
//
//    // 落库: 事务 + 一人一单 + 扣库存 + 保存订单
//    @Override
//    @Transactional
//    public void createVoucherOrder(VoucherOrder voucherOrder) {
//        // 获取用户id,秒杀卷id
//        Long userId = voucherOrder.getUserId();
//        Long voucherId = voucherOrder.getVoucherId();
//
//        // 查看数据库保证一人一单
//        int count = query()
//                .eq("voucher_id",voucherId)
//                .eq("user_id",userId)
//                .count();
//        if(count > 0){
//            log.warn("你已经抢过优惠卷了");
//            return;
//        }
//        // DB 扣库存.使用乐观锁检查是否超卖
//        // Redisson 控制同一用户的并发请求 + 数据库乐观锁控制全局库存并发
//        boolean success = seckillVoucherService.update() // 设置wrapper对象
//                .setSql("stock = stock - 1") // setsql: 设置sql语句
//                .eq("voucher_id", voucherId) // eq: 等于
//                .gt("stock",0) // gt: 大于
//                .update(); //
//        if(!success){
//            log.warn("库存不足");
//            return;
//        }
//        save(voucherOrder);
//    }
//
//    // Redission 分布式锁: 保证同一时刻，同一用户只能有一个线程进入创建订单逻辑
//    private void handleVoucherOrder(VoucherOrder voucherOrder){
//        // 获取用户id
//        Long userId = voucherOrder.getUserId();
//        // 尝试为用户加上redission分布式锁
//        RLock lock= redissonClient.getLock("order:" + userId);
//        // 判断是否加上了锁 -> 能: 用户id唯一 不能: 用户id不唯一
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            log.warn("不允许重复下单");
//            return;
//        }
//        try {
//            // 获取,使用当前类的代理对象,避免事务失效
//            IVoucherOrderService proxy =
//                    (IVoucherOrderService) AopContext.currentProxy();
//            proxy.createVoucherOrder(voucherOrder);
//        } finally {
//            lock.unlock();
//        }
//
//    }
//
//    // 异常处理: 进入pending-list
//    // 只要是消费者组读到了消息，但还没有 ack，这条消息就会进入 Pending List
//    public void handPendingList(){
//        while(true){
//            MapRecord<String, Object, Object> record = null;
//            Map<Object, Object> value = null;
//            VoucherOrder voucherOrder = null;
//            try {
//                // 从头开始读pending-list的消息
//                List<MapRecord<String,Object,Object>> records =
//                        stringRedisTemplate.opsForStream().read(
//                                Consumer.from(GROUP,CONSUMER),
//                                StreamReadOptions.empty().count(1),
//                                StreamOffset.create(QUEUE_NAME,ReadOffset.from("0"))
//                        );
//                if(records == null || records.isEmpty()){
//                    break;
//                }
//                record = records.get(0);
//                value = record.getValue();
//
//                voucherOrder = BeanUtil.fillBeanWithMap(value,new VoucherOrder(),true);
//                // ack
//                handleVoucherOrder(voucherOrder);
//                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME,GROUP,record.getId());
//            } catch (Exception e) {
//                log.warn("pending-list处理异常, recordId={}, values={}",
//                        record == null ? null : record.getId(),
//                        value,
//                        e);
//                try {
//                    Thread.sleep(50);
//                } catch (InterruptedException ex) {
//                   Thread.currentThread().interrupt();
//                   return;
//                }
//            }
//        }
//
//    }
//
//}
