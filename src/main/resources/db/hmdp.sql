/*
 Navicat Premium Data Transfer

 Source Server         : local
 Source Server Type    : MySQL
 Source Server Version : 50622
 Source Host           : localhost:3306
 Source Schema         : hmdp

 Target Server Type    : MySQL
 Target Server Version : 50622
 File Encoding         : 65001

 Date: 14/03/2022 21:38:11
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for merchants
-- ----------------------------
DROP TABLE IF EXISTS `tb_shop_type`;
DROP TABLE IF EXISTS `tb_shop`;
DROP TABLE IF EXISTS `merchants`;
CREATE TABLE `merchants`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of merchants
-- ----------------------------
INSERT INTO `merchants` (`id`, `name`, `status`, `create_time`, `update_time`) VALUES
  (1, 'Brussels Coffee Lab', 1, '2026-01-01 10:00:00', '2026-01-01 10:00:00'),
  (2, 'Antwerp Fitness Club', 1, '2026-01-01 10:00:00', '2026-01-01 10:00:00'),
  (3, 'Ghent Cowork Hub', 1, '2026-01-01 10:00:00', '2026-01-01 10:00:00'),
  (4, 'Leuven Book Store', 1, '2026-01-01 10:00:00', '2026-01-01 10:00:00');

-- ----------------------------
-- Table structure for tb_sign
-- ----------------------------
DROP TABLE IF EXISTS `tb_sign`;
CREATE TABLE `tb_sign`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `year` year NOT NULL COMMENT '签到的年',
  `month` tinyint(2) NOT NULL COMMENT '签到的月',
  `date` date NOT NULL COMMENT '签到的日期',
  `is_backup` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT '是否补签',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_sign
-- ----------------------------

-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `phone` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'email account',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT 'reserved password hash',
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT 'display name',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT 'avatar url',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
  `role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'USER' COMMENT 'USER or ADMIN',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE or DISABLED',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniq_user_email`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 100 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user
-- ----------------------------
INSERT INTO `tb_user` (`id`, `phone`, `password`, `nick_name`, `icon`, `create_time`, `update_time`, `role`, `status`) VALUES
  (1, 'admin@flashsale.dev', '', 'Platform Admin', '', '2026-01-01 10:00:00', '2026-01-01 10:00:00', 'ADMIN', 'ACTIVE'),
  (2, 'alice@example.com', '', 'Alice Demo', '', '2026-01-01 10:00:00', '2026-01-01 10:00:00', 'USER', 'ACTIVE'),
  (3, 'brussels.customer@example.com', '', 'Brussels Customer', '', '2026-01-01 10:00:00', '2026-01-01 10:00:00', 'USER', 'ACTIVE'),
  (4, 'disabled@example.com', '', 'Disabled User', '', '2026-01-01 10:00:00', '2026-01-01 10:00:00', 'USER', 'DISABLED');

-- ----------------------------
-- Table structure for tb_user_info
-- ----------------------------
DROP TABLE IF EXISTS `tb_user_info`;
CREATE TABLE `tb_user_info`  (
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '主键，用户id',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '城市名称',
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '个人介绍，不要超过128个字符',
  `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '性别，0：男，1：女',
  `birthday` date NULL DEFAULT NULL COMMENT '生日',
  `credits` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '积分',
  `level` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '会员级别，0~9级,0代表未开通会员',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user_info
-- ----------------------------
INSERT INTO `tb_user_info` (`user_id`, `city`, `introduce`, `gender`, `birthday`, `credits`, `level`, `create_time`, `update_time`) VALUES
  (1, 'Brussels', 'Platform administrator for local acceptance testing', 0, '1990-01-01', 0, 9, '2026-01-01 10:00:00', '2026-01-01 10:00:00'),
  (2, 'Antwerp', 'Demo customer for flash sale order flow', 1, '1995-05-12', 120, 2, '2026-01-01 10:00:00', '2026-01-01 10:00:00'),
  (3, 'Brussels', 'Coffee lover and early flash sale user', 0, '1992-09-18', 80, 1, '2026-01-01 10:00:00', '2026-01-01 10:00:00'),
  (4, 'Ghent', 'Disabled account for auth status validation', 0, '1988-03-07', 0, 0, '2026-01-01 10:00:00', '2026-01-01 10:00:00');

-- ----------------------------
-- Table structure for offers
-- ----------------------------
DROP TABLE IF EXISTS `tb_seckill_voucher`;
DROP TABLE IF EXISTS `tb_voucher_order`;
DROP TABLE IF EXISTS `tb_voucher`;
DROP TABLE IF EXISTS `payment_webhook_event`;
DROP TABLE IF EXISTS `payment_order`;
DROP TABLE IF EXISTS `orders`;
DROP TABLE IF EXISTS `flash_sale_offers`;
DROP TABLE IF EXISTS `offers`;
CREATE TABLE `offers`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `price_amount` bigint(10) UNSIGNED NOT NULL,
  `face_value_amount` bigint(10) UNSIGNED NOT NULL,
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_offer_merchant`(`merchant_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of offers
-- ----------------------------
INSERT INTO `offers` (`id`, `merchant_id`, `title`, `description`, `rules`, `price_amount`, `face_value_amount`, `status`, `create_time`, `update_time`) VALUES
  (1, 1, 'Coffee Flash Sale Voucher', 'EUR 10 coffee voucher', 'One purchase per user during the flash sale', 475, 1000, 1, '2026-01-01 10:00:00', '2026-01-01 10:00:00'),
  (2, 2, 'Fitness Trial Pass', 'One week fitness trial pass', 'Valid for first-time customers only', 990, 2500, 1, '2026-01-01 10:00:00', '2026-01-01 10:00:00'),
  (3, 3, 'Coworking Day Pass', 'One day desk pass with coffee included', 'Limited weekday access', 1490, 3000, 1, '2026-01-01 10:00:00', '2026-01-01 10:00:00'),
  (4, 4, 'Bookshop Weekend Deal', 'Weekend reading bundle voucher', 'One voucher per user', 799, 1500, 1, '2026-01-01 10:00:00', '2026-01-01 10:00:00');

-- ----------------------------
-- Table structure for flash_sale_offers
-- ----------------------------
CREATE TABLE `flash_sale_offers`  (
  `offer_id` bigint(20) UNSIGNED NOT NULL,
  `stock` int(8) NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `begin_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `end_time` timestamp NOT NULL DEFAULT '2030-01-01 00:00:00',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`offer_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of flash_sale_offers
-- ----------------------------
INSERT INTO `flash_sale_offers` (`offer_id`, `stock`, `create_time`, `begin_time`, `end_time`, `update_time`) VALUES
  (1, 100, '2026-01-01 10:00:00', '2026-01-01 10:00:00', '2030-01-01 00:00:00', '2026-01-01 10:00:00'),
  (2, 80, '2026-01-01 10:00:00', '2026-01-01 10:00:00', '2030-01-01 00:00:00', '2026-01-01 10:00:00'),
  (3, 60, '2026-01-01 10:00:00', '2026-01-01 10:00:00', '2030-01-01 00:00:00', '2026-01-01 10:00:00'),
  (4, 120, '2026-01-01 10:00:00', '2026-01-01 10:00:00', '2030-01-01 00:00:00', '2026-01-01 10:00:00');

-- ----------------------------
-- Table structure for orders
-- ----------------------------
CREATE TABLE `orders`  (
  `id` bigint(20) NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `offer_id` bigint(20) UNSIGNED NOT NULL,
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1,
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1,
  `pay_amount` bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'EUR',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expire_time` timestamp NULL DEFAULT NULL,
  `pay_time` timestamp NULL DEFAULT NULL,
  `use_time` timestamp NULL DEFAULT NULL,
  `refund_time` timestamp NULL DEFAULT NULL,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_user_offer`(`user_id`, `offer_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of orders
-- ----------------------------

-- ----------------------------
-- Table structure for payment_order
-- ----------------------------
CREATE TABLE `payment_order` (
  `id` bigint(20) NOT NULL,
  `order_id` bigint(20) NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `provider_payment_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL,
  `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'EUR',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `checkout_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `expires_at` timestamp NULL DEFAULT NULL,
  `paid_at` timestamp NULL DEFAULT NULL,
  `failure_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniq_payment_order_order`(`order_id`) USING BTREE,
  UNIQUE INDEX `uniq_provider_payment`(`provider`, `provider_payment_id`) USING BTREE,
  INDEX `idx_payment_order_user`(`user_id`) USING BTREE,
  INDEX `idx_payment_order_status`(`status`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of payment_order
-- ----------------------------

-- ----------------------------
-- Table structure for payment_webhook_event
-- ----------------------------
CREATE TABLE `payment_webhook_event` (
  `id` bigint(20) NOT NULL,
  `provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `event_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `event_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `provider_payment_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `order_id` bigint(20) DEFAULT NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `raw_payload` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci,
  `error_message` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `processed_at` timestamp NULL DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniq_provider_event`(`provider`, `event_id`) USING BTREE,
  INDEX `idx_webhook_order`(`order_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of payment_webhook_event
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
