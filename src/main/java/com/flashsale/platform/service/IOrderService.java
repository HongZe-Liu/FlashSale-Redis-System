package com.flashsale.platform.service;

import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Order;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IOrderService extends IService<Order> {

    Result placeFlashSaleOrder(Long offerId, Long userId);

    boolean createOrder(Order order);
}
