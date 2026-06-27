package com.flashsale.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.platform.entity.Offer;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface OfferMapper extends BaseMapper<Offer> {

    List<Offer> queryOffersByMerchant(@Param("merchantId") Long merchantId);
}
