package com.flashsale.platform.service;

import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Merchant;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IMerchantService extends IService<Merchant> {

    /**
     * 根据id查询店铺详情，并使用redis帮助进行缓存
     */
    Result queryById(Long id);

    /**
     *  更新店铺信息
     */
    Result update(Merchant merchant);


}
