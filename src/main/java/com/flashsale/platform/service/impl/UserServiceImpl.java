package com.flashsale.platform.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.platform.entity.User;
import com.flashsale.platform.mapper.UserMapper;
import com.flashsale.platform.service.IUserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public User createUserByEmail(String email) {
        User user = new User();
        user.setEmail(email);
        user.setNickName("User_" + RandomUtil.randomString(8));
        user.setRole("USER");
        user.setStatus("ACTIVE");
        this.save(user);
        return user;
    }
}

