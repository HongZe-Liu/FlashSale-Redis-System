package com.flashsale.platform.controller;


import com.flashsale.platform.dto.Result;
import com.flashsale.platform.dto.UserDTO;
import com.flashsale.platform.entity.Offer;
import com.flashsale.platform.service.IOfferService;
import com.flashsale.platform.service.IOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/flash-sales")
public class FlashSaleController {

    @Autowired
    private IOfferService offerService;

    @Autowired
    private IOrderService orderService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result createFlashSaleOffer(@RequestBody Offer offer) {
        offerService.createFlashSaleOffer(offer);
        return Result.ok(offer.getId());
    }

    @PostMapping("/{offerId}/orders")
    public Result placeFlashSaleOrder(@PathVariable("offerId") Long offerId,
                                      @AuthenticationPrincipal UserDTO user) {
        if (user == null) {
            return Result.fail("User is not authenticated");
        }
        return orderService.placeFlashSaleOrder(offerId, user.getId());
    }

    @PostMapping("/{offerId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public Result publishFlashSaleOffer(@PathVariable("offerId") Long offerId) {
        return offerService.publishFlashSaleOffer(offerId);
    }

}
