package com.flashsale.payment.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashsale.payment.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    // 使用手机号注册
    public User createUserByPhone(String phone);

}
