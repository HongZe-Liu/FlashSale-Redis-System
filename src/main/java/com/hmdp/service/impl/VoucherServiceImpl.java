package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.ZoneOffset;
import java.util.List;



import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    // 新增秒杀卷的同时将优惠卷信息存入redis
    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        // 关联普通卷id
        seckillVoucher.setVoucherId(voucher.getId());
        // 设置库存
        seckillVoucher.setStock(voucher.getStock());
        // 设置开始时间
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        // 设置结束时间
        seckillVoucher.setEndTime(voucher.getEndTime());
        // 保存到数据库表中
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀优惠券信息到Reids，Key名中包含优惠券ID，Value为优惠券的剩余数量
        // 将库存信息添加到redis
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
        // 将开始，结束时间放到redis中,用hash存
        // opsForHash().put(key, field, value)
        String timeKey = "seckill:voucher:" + voucher.getId();
        stringRedisTemplate.opsForHash().put(timeKey,"begin", String.valueOf(voucher.getBeginTime().toEpochSecond(ZoneOffset.UTC)));
        stringRedisTemplate.opsForHash().put(timeKey,"end", String.valueOf(voucher.getEndTime().toEpochSecond(ZoneOffset.UTC)));
    }}
