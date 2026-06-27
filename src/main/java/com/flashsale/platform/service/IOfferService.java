package com.flashsale.platform.service;

import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Offer;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IOfferService extends IService<Offer> {

    Result queryOffersByMerchant(Long merchantId);

    void createFlashSaleOffer(Offer offer);

    Result publishFlashSaleOffer(Long offerId);
}
