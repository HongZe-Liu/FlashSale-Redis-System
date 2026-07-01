package com.flashsale.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Merchant;
import com.flashsale.platform.mapper.MerchantMapper;
import com.flashsale.platform.service.IMerchantService;
import com.flashsale.platform.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.flashsale.platform.utils.RedisConstants.CACHE_MERCHANT_KEY;
import static com.flashsale.platform.utils.RedisConstants.CACHE_MERCHANT_TTL;

/**
 * <p>
 * Merchant service implementation.
 * </p>
 */
@Service
public class MerchantServiceImpl extends ServiceImpl<MerchantMapper, Merchant> implements IMerchantService {
    private final StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    public MerchantServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryById(Long id) {
        Merchant merchant = cacheClient.queryWithPassThrough(
                CACHE_MERCHANT_KEY,
                id,
                Merchant.class,
                this::getById,
                CACHE_MERCHANT_TTL,
                TimeUnit.MINUTES
        );
        if (merchant == null) {
            return Result.fail("Merchant does not exist");
        }
        return Result.ok(merchant);
    }

    @Override
    public Result update(Merchant merchant) {
        if (merchant.getId() == null) {
            return Result.fail("Merchant id is required");
        }

        updateById(merchant);
        stringRedisTemplate.delete(CACHE_MERCHANT_KEY + merchant.getId());
        return Result.ok();
    }
}
