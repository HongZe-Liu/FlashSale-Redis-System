package com.flashsale.payment.controller;


import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.Merchant;
import com.flashsale.payment.service.IMerchantService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
@RestController
@RequestMapping("/merchants")
public class MerchantController {

    @Resource
    public IMerchantService merchantService;

    /**
     * Query merchant by id.
     */
    @GetMapping("/{id}")
    public Result queryMerchantById(@PathVariable("id") Long id) {
        return Result.ok(merchantService.queryById(id));
    }

    /**
     * Create merchant.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result createMerchant(@RequestBody Merchant merchant) {
        merchantService.save(merchant);
        return Result.ok(merchant.getId());
    }

    /**
     * Update merchant.
     */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result updateMerchant(@RequestBody Merchant merchant) {
        return merchantService.update(merchant);
    }
}
