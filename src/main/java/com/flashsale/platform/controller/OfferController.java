package com.flashsale.platform.controller;
import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Offer;
import com.flashsale.platform.service.IOfferService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
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
