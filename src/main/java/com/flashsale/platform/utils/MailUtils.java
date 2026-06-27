package com.flashsale.platform.utils;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.util.Properties;

@Slf4j
public final class MailUtils {

    private static final String VERIFICATION_CHARS =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private MailUtils() {
    }

    public static void sendMail(String email, String code) throws MessagingException {
        log.info("Sending verification email, email={}", MaskUtils.maskEmail(email));

        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "465");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.debug", "false");

        final String from = System.getenv("GMAIL_FROM");
        final String appPass = System.getenv("GMAIL_APP_PASS");

        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, appPass);
            }
        };

        Session mailSession = Session.getInstance(props, authenticator);
        MimeMessage message = new MimeMessage(mailSession);
        message.setFrom(new InternetAddress(from));
        InternetAddress to = new InternetAddress(email);
        message.setRecipient(Message.RecipientType.TO, to);
        message.setSubject("Flash Sale Platform verification code");
        message.setText("Your verification code is: " + code + "\nIt expires in two minutes.", "UTF-8");
        Transport.send(message);
    }

    public static String achieveCode() {
        StringBuilder code = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            code.append(VERIFICATION_CHARS.charAt(SECURE_RANDOM.nextInt(VERIFICATION_CHARS.length())));
        }
        return code.toString();
    }
}
