package com.flashsale.payment.service.impl;

import com.flashsale.payment.entity.UserInfo;
import com.flashsale.payment.mapper.UserInfoMapper;
import com.flashsale.payment.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
