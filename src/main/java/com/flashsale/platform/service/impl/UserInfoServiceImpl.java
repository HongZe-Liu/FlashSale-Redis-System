package com.flashsale.platform.service.impl;

import com.flashsale.platform.entity.UserInfo;
import com.flashsale.platform.mapper.UserInfoMapper;
import com.flashsale.platform.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
