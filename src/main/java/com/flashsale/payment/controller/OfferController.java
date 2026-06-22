package com.flashsale.payment.controller;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.Offer;
import com.flashsale.payment.service.IOfferService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
@RestController
@RequestMapping("/offers")
public class OfferController {

    @Resource
    private IOfferService offerService;

    /**
     * Create an offer.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result createOffer(@RequestBody Offer offer) {
        offerService.save(offer);
        return Result.ok(offer.getId());
    }

    /**
     * Query offers by merchant.
     */
    @GetMapping("/merchant/{merchantId}")
    public Result queryOffersByMerchant(@PathVariable("merchantId") Long merchantId) {
       return offerService.queryOffersByMerchant(merchantId);
    }
}
