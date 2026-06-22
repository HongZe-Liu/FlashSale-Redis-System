package com.flashsale.payment.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.Merchant;
import com.flashsale.payment.mapper.MerchantMapper;
import com.flashsale.payment.service.IMerchantService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.payment.utils.CacheClient;
import com.flashsale.payment.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.flashsale.payment.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class MerchantServiceImpl extends ServiceImpl<MerchantMapper, Merchant> implements IMerchantService {
    private final StringRedisTemplate stringRedisTemplate;

    public MerchantServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Resource
    private CacheClient cacheClient;


    /**
     * 根据id查询店铺详情，并使用redis帮助进行缓存
     */
    @Override
    public Result queryById(Long id){
         Merchant merchant = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id, Merchant.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if (merchant == null){
            return Result.fail("Merchant does not exist");
        };
        return Result.ok(merchant);
    }

    /**
     * 更新店铺信息
     */
    @Override
    public Result update(Merchant merchant) {
        // 1.判断是否为空
    if(merchant.getId() == null){
        return Result.fail("Merchant id is required");
    }
    // 2.修改数据库数据
        updateById(merchant);
    // 3. 删除redis数据。形成aside ache
        stringRedisTemplate.delete(CACHE_SHOP_KEY + merchant.getId());
        return Result.ok();
    }



}
