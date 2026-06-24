package com.flashsale.payment.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.FlashSaleOffer;
import com.flashsale.payment.entity.Offer;
import com.flashsale.payment.entity.Order;
import com.flashsale.payment.mapper.OfferMapper;
import com.flashsale.payment.service.IFlashSaleOfferService;
import com.flashsale.payment.service.IOfferService;
import com.flashsale.payment.service.IOrderService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;



import static com.flashsale.payment.utils.RedisConstants.FLASH_SALE_CACHE_RETAIN_DAYS;
import static com.flashsale.payment.utils.RedisConstants.FLASH_SALE_OFFER_KEY;
import static com.flashsale.payment.utils.RedisConstants.FLASH_SALE_ORDER_KEY;
import static com.flashsale.payment.utils.RedisConstants.FLASH_SALE_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class OfferServiceImpl extends ServiceImpl<OfferMapper, Offer> implements IOfferService {

    @Resource
    private IFlashSaleOfferService flashSaleOfferService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IOrderService orderService;

    @Override
    public Result queryOffersByMerchant(Long merchantId) {
        List<Offer> offers = getBaseMapper().queryOffersByMerchant(merchantId);
        return Result.ok(offers);
    }

    @Override
    @Transactional
    public void createFlashSaleOffer(Offer offer) {
        save(offer);

        FlashSaleOffer flashSaleOffer = new FlashSaleOffer();
        flashSaleOffer.setOfferId(offer.getId());
        flashSaleOffer.setStock(offer.getStock());
        flashSaleOffer.setBeginTime(offer.getBeginTime());
        flashSaleOffer.setEndTime(offer.getEndTime());

        flashSaleOfferService.save(flashSaleOffer);
    }

    @Override
    public Result publishFlashSaleOffer(Long offerId) {
        if (offerId == null) {
            return Result.fail("Offer id is required");
        }
        Offer offer = getById(offerId);
        if (offer == null) {
            return Result.fail("Offer does not exist");
        }
        if (!Integer.valueOf(1).equals(offer.getStatus())) {
            return Result.fail("Offer is not active");
        }
        FlashSaleOffer flashSaleOffer = flashSaleOfferService.getById(offerId);
        if (flashSaleOffer == null) {
            return Result.fail("Flash sale offer does not exist");
        }
        if (flashSaleOffer.getStock() == null || flashSaleOffer.getStock() <= 0) {
            return Result.fail("Flash sale stock must be greater than zero");
        }
        if (flashSaleOffer.getBeginTime() == null || flashSaleOffer.getEndTime() == null) {
            return Result.fail("Flash sale time range is required");
        }
        if (!flashSaleOffer.getBeginTime().isBefore(flashSaleOffer.getEndTime())) {
            return Result.fail("Flash sale begin time must be before end time");
        }
        if (!flashSaleOffer.getEndTime().isAfter(LocalDateTime.now())) {
            return Result.fail("Flash sale has already ended");
        }
        long ttlSeconds = warmUpFlashSaleOfferCache(
                flashSaleOffer.getOfferId(),
                flashSaleOffer.getStock(),
                flashSaleOffer.getBeginTime(),
                flashSaleOffer.getEndTime()
        );
        rebuildFlashSaleOrderCache(flashSaleOffer.getOfferId(), ttlSeconds);
        return Result.ok();
    }

    private long warmUpFlashSaleOfferCache(Long offerId, Integer stock,
                                           LocalDateTime beginTime, LocalDateTime endTime) {
        if (offerId == null || stock == null || beginTime == null || endTime == null) {
            throw new IllegalArgumentException("Flash sale offer cache warm-up arguments are incomplete");
        }
        String stockKey = FLASH_SALE_STOCK_KEY + offerId;
        String offerKey = FLASH_SALE_OFFER_KEY + offerId;
        String orderKey = FLASH_SALE_ORDER_KEY + offerId;
        long retainSeconds = TimeUnit.DAYS.toSeconds(FLASH_SALE_CACHE_RETAIN_DAYS);
        long ttlSeconds = flashSaleCacheTtlSeconds(endTime);

        stringRedisTemplate.opsForValue().set(stockKey, stock.toString());
        stringRedisTemplate.opsForHash().put(offerKey, "begin", String.valueOf(beginTime.toEpochSecond(ZoneOffset.UTC)));
        stringRedisTemplate.opsForHash().put(offerKey, "end", String.valueOf(endTime.toEpochSecond(ZoneOffset.UTC)));
        stringRedisTemplate.opsForHash().put(offerKey, "retainSeconds", String.valueOf(retainSeconds));

        stringRedisTemplate.expire(stockKey, ttlSeconds, TimeUnit.SECONDS);
        stringRedisTemplate.expire(offerKey, ttlSeconds, TimeUnit.SECONDS);
        stringRedisTemplate.expire(orderKey, ttlSeconds, TimeUnit.SECONDS);
        return ttlSeconds;
    }

    private void rebuildFlashSaleOrderCache(Long offerId, long ttlSeconds) {
        String orderKey = FLASH_SALE_ORDER_KEY + offerId;
        List<Order> orders = orderService.query()
                .select("user_id")
                .eq("offer_id", offerId)
                .list();
        String[] userIds = orders.stream()
                .map(Order::getUserId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .distinct()
                .toArray(String[]::new);
        if (userIds.length > 0) {
            stringRedisTemplate.opsForSet().add(orderKey, userIds);
            stringRedisTemplate.expire(orderKey, ttlSeconds, TimeUnit.SECONDS);
        }
    }

    private long flashSaleCacheTtlSeconds(LocalDateTime endTime) {
        LocalDateTime expireTime = endTime.plusDays(FLASH_SALE_CACHE_RETAIN_DAYS);
        long ttlSeconds = Duration.between(LocalDateTime.now(), expireTime).getSeconds();
        return Math.max(ttlSeconds, 1L);
    }
}
