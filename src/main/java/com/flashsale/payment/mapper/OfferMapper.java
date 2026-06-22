package com.flashsale.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.payment.entity.Offer;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface OfferMapper extends BaseMapper<Offer> {

    List<Offer> queryOffersByMerchant(@Param("merchantId") Long merchantId);
}
