package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import io.jsonwebtoken.Jwt;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    @Autowired
    private UserMapper userMapper;

    /**
     * 发送邮箱验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 1. 邮箱校验
        if(RegexUtils.isEmailInvalid(phone)) {
            return Result.fail("邮箱格式不正确");
        }

        // 同一邮箱短时间内只能发送一次验证码，防止刷邮件。
        String limitKey = LOGIN_CODE_LIMIT_KEY + phone; // 拼接key
        Boolean allowed = stringRedisTemplate.opsForValue() // 写入redis并设置过期时间
                // 判断是否在限制时间内(60秒内只能发送一次)
                .setIfAbsent(limitKey, "1", LOGIN_CODE_LIMIT_TTL, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(allowed)) {
            return Result.fail("验证码发送过于频繁，请稍后再试");
        }

        // 2. 生成验证码
        String code = MailUtils.achieveCode();

        // 3. 发送邮件 + 成功后再存 session
        try{
            MailUtils.sendMail(phone,code);
            stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
            log.info("已经发送验证码：{}", code);
        }catch(MessagingException e){
            stringRedisTemplate.delete(limitKey);
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
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session, HttpServletResponse response ){

        // 1. 获取账号和验证码 + 验证账号
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();

        // 2. 验证邮箱
        if(RegexUtils.isEmailInvalid(phone)) {
            log.error("账号格式不正确");
            return Result.fail("账号不正确");
        }

        // 3. 判断当前邮箱是否因连续输错验证码被短时锁定
        String failKey = LOGIN_CODE_FAIL_KEY + phone; // 拼接key
        String failCountStr = stringRedisTemplate.opsForValue().get(failKey);
        if (failCountStr != null && Long.parseLong(failCountStr) >= LOGIN_CODE_MAX_RETRY) {
            return Result.fail("验证码错误次数过多，请稍后再试");
        }

        // 4. 校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        log.info("code={} cacheCode={}", code, cacheCode);
        if(code == null || !code.equals(cacheCode)) {
            Long failCount = stringRedisTemplate.opsForValue().increment(failKey);
            if (Long.valueOf(1L).equals(failCount)) {
                stringRedisTemplate.expire(failKey, LOGIN_CODE_FAIL_TTL, TimeUnit.MINUTES);
            }
            if (failCount != null && failCount >= LOGIN_CODE_MAX_RETRY) {
                return Result.fail("验证码错误次数过多，请稍后再试");
            }
            return Result.fail("验证码不正确");
        }
        stringRedisTemplate.delete(failKey);

        // 5. 查询用户 -> Lambda + Mybatisplus
        LambdaQueryWrapper <User> queryWrapper = new LambdaQueryWrapper <>();
        queryWrapper.eq(User::getPhone, phone);
        User user = userService.getOne(queryWrapper);

        // 6. 查询用户是否存在，不存在则创建
        if(user == null) {
            log.info("用户不存在，创建新用户");
            user = userService.createUserByPhone(phone);
        }
        // 6.1 将用户存入到redis

        // 5.1.1 生成access / refresh token 并放入redis
        String token = JwtUtils.generateToken(user.getId(),user.getNickName(),"user");
        String jti  = JwtUtils.getJti(token);
        String refreshToken = refreshTokenUtils.generateRftoken();

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        String icon = userDTO.getIcon() == null ? "" : userDTO.getIcon();
        String nickName = userDTO.getNickName() == null ? "" : userDTO.getNickName();
        HashMap<String,String> userMap = new HashMap<>(); // 创建hashmap
        userMap.put("id", user.getId().toString()); // 存入图标
        userMap.put("nickName", nickName); // 存入ID
        userMap.put("icon", icon); // 存入用户名

        // 5.1.2 创建Key -> 将 常量和token 组合
        String tokenKey = LOGIN_USER_KEY + jti;
        // 5.1.3 将key 和 value 写入redis
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 5.1.4 设置过期时间
        stringRedisTemplate.expire(tokenKey,60, TimeUnit.MINUTES);
        // 5.1.5 存入Refresh token
        stringRedisTemplate.opsForValue().set(
                LOGIN_REFRESH_KEY + refreshToken, String.valueOf(user.getId()),
                REFRESH_TTL,
                TimeUnit.DAYS
        );
        // 5.1.6 写入HTTP ONLY COOKIE
        ResponseCookie cookie = ResponseCookie.from("refresh_token" , refreshToken)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/user/refresh")
                .maxAge(Duration.ofDays(REFRESH_TTL))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        // 5.1.7 登录成功删除验证码信息
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        return Result.ok(token);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request,
                         @CookieValue(value = "refresh_token", required = false) String refreshToken,
                         HttpServletResponse response){
       // 1. 获取解析请求头request
        String token = request.getHeader("Authorization");
       // 2. 验证token
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            if (JwtUtils.isValid(token)) {
                String jti = JwtUtils.getJti(token);
                stringRedisTemplate.delete(LOGIN_USER_KEY + jti);
            }
        }

        // 3. 检查refresh token
        if(refreshToken != null && !refreshToken.isBlank()){
            stringRedisTemplate.delete(LOGIN_REFRESH_KEY + refreshToken);
        }

        // 4. 清除 refresh Cookie -> 将cookie设置为空
        ResponseCookie cookie = ResponseCookie.from("refresh_token","")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/user/refresh")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());
        return Result.ok("成功退出");
    }

    /**
     * 获取当前登录用户并返回
     */
    @GetMapping("/me")
    public Result me(@AuthenticationPrincipal UserDTO user){
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

    /**
     * refresh token 接口
     * 将refresh token 放入 HttpOnly Cookie 保证安全
     */
    @PostMapping("/refresh")
    public Result refresh(@CookieValue(value = "refresh_token", required = false)
                                   String refreshToken,
                                   HttpServletResponse response){

        // 1. 验证 refreshtoken
        if (refreshToken == null || refreshToken.isBlank()){
            return Result.fail("用户未登陆");
        }

        // 2. redis 校验 -> 并获取 userId
        String refreshKey = LOGIN_REFRESH_KEY + refreshToken;
        String userIdStr = stringRedisTemplate.opsForValue().get(refreshKey);
        if(userIdStr == null || userIdStr.isBlank()){
            return Result.fail("用户不存在");
        }

        // 3. 生成新的 access + refreshtoken (实现轮换)
        Long userId = Long.valueOf(userIdStr);
        User user = userService.getById(userId);
        if(user == null){
            return Result.fail("用户不存在，请重新登录");
        }
        String newAccessToken = JwtUtils.generateToken(user.getId(),user.getNickName(),"user");
        String newRefreshToken = refreshTokenUtils.generateRftoken();

        // 4. 写入redis

        String accessJti = JwtUtils.getJti(newAccessToken);
        // 空值检查
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String icon = userDTO.getIcon() == null ? "" : userDTO.getIcon();
        String nickName = userDTO.getNickName() == null ? "" : userDTO.getNickName();

        // 4.1 存入map
        Map<String,String> userMap = new HashMap<>();
        userMap.put("id",user.getId().toString());
        userMap.put("nickName",nickName);
        userMap.put("icon",icon);
        // 4.2 将user存入Redis + 添加过期时间
        stringRedisTemplate.opsForHash().putAll( LOGIN_USER_KEY+ accessJti,userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + accessJti ,LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 4.3 删除旧refresh
        stringRedisTemplate.delete(refreshKey);
        stringRedisTemplate.opsForValue().set(LOGIN_REFRESH_KEY + newRefreshToken, userIdStr, REFRESH_TTL, TimeUnit.DAYS);

        // 5. 回写 Http cookie only
        ResponseCookie cookie = ResponseCookie.from("refresh_token", newRefreshToken )
                .httpOnly(true) // 前端 JavaScript 不能读取这个 Cookie
                .secure(false) // 只允许 HTTPS 发送 Cookie
                .sameSite("Lax") // 防止CSRF 攻击
                .path("/user/refresh") // 只有这个路径的访问会带cookie
                .maxAge(Duration.ofDays(REFRESH_TTL)) // cookie 有效期
                .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()); // 写入响应头
        // 6, 返回新的access
        return Result.ok(newAccessToken);
    }
}
