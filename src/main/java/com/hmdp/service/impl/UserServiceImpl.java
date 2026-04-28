package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private IUserService userService;

    @Override
    public User createUserByPhone(String phone) {
            // 创建新的用户对象
            User user = new User();
            // 写入手机号
            user.setPhone(phone);
            // 写入昵称（默认）
            user.setNickName("User_" + RandomUtil.randomString(8));
            // 添加角色
            user.setRole("USER");
            // 添加状态
            user.setStatus("ACTIVE");
            // 保存到数据库
            this.save(user);
            return user;
        }
}

