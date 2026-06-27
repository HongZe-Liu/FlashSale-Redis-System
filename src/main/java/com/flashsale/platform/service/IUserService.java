package com.flashsale.platform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashsale.platform.entity.User;

public interface IUserService extends IService<User> {

    User createUserByEmail(String email);

}
