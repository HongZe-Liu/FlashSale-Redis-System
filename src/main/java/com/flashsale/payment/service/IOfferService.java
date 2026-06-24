package com.flashsale.payment.service;

import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.Offer;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IOfferService extends IService<Offer> {

    Result queryOffersByMerchant(Long merchantId);

    void createFlashSaleOffer(Offer offer);

    Result publishFlashSaleOffer(Long offerId);
}
