package com.hmdp.utils;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Properties;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * MailUtils：发送邮件工具类（使用 JavaMail）
 *
 * 核心流程（记住它）：
 * Properties(配置) -> Authenticator(认证) -> Session(会话) -> MimeMessage(邮件) -> Transport.send(发送)
 */
public class MailUtils {

    // mian方法测试
    public static void main(String[] args) throws MessagingException {

        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("java.vendor  = " + System.getProperty("java.vendor"));
        // 测试方法
        sendMail("hongzeliu2013@gmail.com", new MailUtils().achieveCode());
    }

    // sendTestMail 发送邮件

    public static void sendMail(String email, String code) throws MessagingException {

        // 1 SMTP 配置（邮件发送协议）：Properties -> 告诉 java 如何连接服务器

        // 1.1 创建Properties 类用于记录邮箱的一些属性
        Properties props = new Properties();

        // 1.1 设置SMTP服务器
        props.put("mail.smtp.host", "smtp.gmail.com");

        // 1.2 设置通过端口
        props.put("mail.smtp.port", "465");

        // 1.3 设置是否需要登录认证
        props.put("mail.smtp.auth", "true");

        // 1.4 开启SSL
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.debug", "true");

        final String from = "hongzeliu2013@gmail.com";
        final  String appPass = "uddlnlwjxqtunivr";


        // 2 SMTP 认证（Authenticator）-> 告诉 java 登录用的账号/授权码是什么

        // 2.1 构建授权信息进行认证 -> 使用匿名类构建
        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, appPass);
            }
        };


        // 3. 创建Session会话 -> 把「邮件服务器配置 + 身份认证方式」封装成一个统一、可复用的邮件运行环境

        // 3.1 使用配置和授权信息创建会话
        Session mailSession = Session.getInstance(props, authenticator);

        // 3.2 创建邮件消息
        MimeMessage message = new MimeMessage(mailSession);

        // 3.3 设置发件人
        message.setFrom(new InternetAddress(from));

        // 3.4 设置收件人邮箱
        InternetAddress to = new InternetAddress(email);
        message.setRecipient(Message.RecipientType.TO, to);

        // 3.5 设置邮件标题
        message.setSubject("测试邮件");

        // 3.6 设置具体邮件内容
        message.setText("尊敬的用户:你好!\n注册验证码为:" + code + "(有效期为一分钟,请勿告知他人)", "UTF-8");

        // 3.7 发送邮件
        Transport.send(message);
    }

    // 4. 随机生成验证码 -> 5位字母 + 数字验证码字符串
    public static String achieveCode() {
        // 4.1 设置随机生成字符池
        String[] beforeShuffle = new String[]{"2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F",
                "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "a",
                "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v",
                "w", "x", "y", "z"};
        // 4.2 放在集合框架内
        List<String> list = Arrays.asList(beforeShuffle);

        // 4.3 打乱集合顺序，生成随机字母数字组合
        Collections.shuffle(list);

        // 4.4 创建可变字符串，用于高效拼接字符串 -> 修改源字符串
        StringBuilder sb= new StringBuilder();

        // 4.5 循环遍历拼接
        for(String s : list) {
            sb.append(s);
        }
        return sb.substring(3,8);
        /*
        ⚠️在返回时需要转换为String字符串而不是用可变字符串返回
           因此调用 subString返回某下标区间的 String
        */
    }
}
