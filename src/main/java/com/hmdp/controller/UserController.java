package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.MailUtils;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.hmdp.utils.RedisConstants;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate; // 注入redis

    /**
     * 发送邮箱验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 1. 邮箱校验
        if(RegexUtils.isEmailInvalid(phone)) {
            return Result.fail("邮箱格式不正确");
        }

        // 2. 生成验证码
        String code = MailUtils.achieveCode();

        // 3. 发送邮件 + 成功后再存 session
        try{
            MailUtils.sendMail(phone,code);
            stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
            log.info("已经发送验证码：{}", code);
        }catch(MessagingException e){
            log.error("邮件发送失败，email={}", phone, e);
            return Result.fail("邮件发送失败");
        }
        return Result.ok("验证码发送成功");
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){

        // 1. 获取账号和验证码 + 验证账号
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        // 2. 验证邮箱
        if(RegexUtils.isEmailInvalid(phone)) {
            log.error("账号格式不正确");
            return Result.fail("账号不正确");
        }

        // 3. 校验验证码
        log.info("code={} cacheCode={}", code, cacheCode);
        if(code == null || !code.equals(cacheCode)) {
            return Result.fail("验证码不正确");
        }

        // 4. 查询用户 -> Lambda + Mybatisplus
        LambdaQueryWrapper <User> queryWrapper = new LambdaQueryWrapper <>();
        queryWrapper.eq(User::getPhone, phone);
        User user = userService.getOne(queryWrapper);

        // 5. 查询用户是否存在，不存在则创建
        if(user == null) {
            log.info("用户不存在，创建新用户");
            user = userService.createUserByPhone(phone);
        }
        // 5.1 将用户存入到redis

        // 5.1.1 生成随机token作为登录令牌
        String token = UUID.randomUUID().toString();
        // 5.1.2 创建value -> 将DTO 数据转化为HashMap储存
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        HashMap<String,String> userMap = new HashMap<>(); // 创建hashmap
        userMap.put("Icon", userDTO.getIcon()); // 存入图标
        userMap.put("id", String.valueOf(user.getId())); // 存入ID
        userMap.put("nickName", userDTO.getNickName()); // 存入用户名
        // 5.1.2 创建Key -> 将 常量和token 组合
        String tokenKey = LOGIN_USER_KEY + token;
        // 5.1.3 将key 和 value 写入redis
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 5.1.4 设置过期时间
        stringRedisTemplate.expire(tokenKey,30, TimeUnit.MINUTES);

        log.info("token={}", token);
        log.info("redisKey={}", tokenKey);
        log.info("redisExists={}", Boolean.TRUE.equals(stringRedisTemplate.hasKey(tokenKey)));
        log.info("redisTTL={}", stringRedisTemplate.getExpire(tokenKey, TimeUnit.MINUTES));

        // 5.1.5 登录成功删除验证码信息
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        return Result.ok(token);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(){
        // TODO 实现登出功能
        return Result.fail("功能未完成");
    }

    /**
     * 获取当前登录用户并返回
     */
    @GetMapping("/me")
    public Result me(){
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
}
