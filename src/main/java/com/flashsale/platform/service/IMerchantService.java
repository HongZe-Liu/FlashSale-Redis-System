package com.flashsale.platform.service;

import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Merchant;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IMerchantService extends IService<Merchant> {

    Result queryById(Long id);

    Result update(Merchant merchant);
}
