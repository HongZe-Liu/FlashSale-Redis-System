package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;



@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /*
    *  使用redis 进行商户列表缓存
    */
    @Override
    public Result queryList() {


        // 1. 先从 redis 中查值
        List<String> shopTypes = stringRedisTemplate.opsForList()
                .range(CACHE_SHOP_TYPE_KEY, 0, -1);

        // 2. 如果找到转为 ShopType 类型直接返回
        if (shopTypes != null && !shopTypes.isEmpty()) {
            List<ShopType> result = new ArrayList<>(shopTypes.size());
            for (String json : shopTypes) {
                result.add(JSONUtil.toBean(json, ShopType.class));
            }
            return Result.ok(result);
        }

        // 3. 如果未找到，查询数据库
        List<ShopType> tmp = query().orderByAsc("sort").list();
        if (tmp == null || tmp.isEmpty()) {
            return Result.fail("店铺类型不存在");
        }

        // 4. 查到了转为 json 字符串，存入 redis，并设置TTL 过期时间
        List<String> toCache = new ArrayList<>(tmp.size());
        for (ShopType shopType : tmp) {
            toCache.add(JSONUtil.toJsonStr(shopType));
        }
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, toCache);

        // 5. 返回
        return Result.ok(tmp);
    }
}
