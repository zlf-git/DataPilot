-- ============================================================
-- CDC 源表重建：标准维度建模 = 1 事实表 + 3 维度表
-- 事实表 orders 只存维度外键（product_id / channel / province），
-- 维度描述（商品名 / 类目 / 渠道名 / 省份名 / 大区）全部放到 dim_* 维度表，
-- 下游通过维表 join 拼回描述，体现「事实表存外键、维度表存描述」的建模规范。
-- ============================================================

DROP DATABASE IF EXISTS cdc_source;
CREATE DATABASE cdc_source CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE cdc_source;

-- ==================== 维度表 ====================
-- 商品维度
CREATE TABLE dim_product (
    product_id    VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '商品ID',
    product_name  VARCHAR(128) NOT NULL COMMENT '商品名',
    category_name VARCHAR(64)  NOT NULL COMMENT '类目名'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 渠道维度
CREATE TABLE dim_channel (
    channel_code VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '渠道编码',
    channel_name VARCHAR(32) NOT NULL COMMENT '渠道名'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 省份维度
CREATE TABLE dim_province (
    province_code VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '省份编码',
    province_name VARCHAR(64) NOT NULL COMMENT '省份名',
    region        VARCHAR(32) NOT NULL COMMENT '大区'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 事实表 ====================
CREATE TABLE orders (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id      VARCHAR(64) NOT NULL COMMENT '平台订单号',
    platform_code VARCHAR(32) DEFAULT 'DOUYIN' COMMENT '平台编码',
    shop_id       VARCHAR(32) DEFAULT 'insta360_official' COMMENT '店铺ID',
    buyer_uid     VARCHAR(64) DEFAULT '' COMMENT '买家ID',
    product_id    VARCHAR(64) DEFAULT '' COMMENT '商品ID(外键→dim_product)',
    channel       VARCHAR(32) DEFAULT 'app' COMMENT '渠道编码(外键→dim_channel)',
    province      VARCHAR(32) DEFAULT 'GD' COMMENT '省份编码(外键→dim_province)',
    total_amount  DECIMAL(16,2) DEFAULT 0 COMMENT '订单总金额',
    pay_status    VARCHAR(32) DEFAULT 'PAID' COMMENT '支付状态',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_order_id (order_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 维度数据 ====================
INSERT INTO dim_product (product_id, product_name, category_name) VALUES
('p0', '机械键盘', '电脑外设'),
('p1', '4K显示器', '电脑外设'),
('p2', '人体工学椅', '办公家具');

INSERT INTO dim_channel (channel_code, channel_name) VALUES
('app', 'APP'),
('web', '网页'),
('miniapp', '小程序');

INSERT INTO dim_province (province_code, province_name, region) VALUES
('GD', '广东', '华南'),
('BJ', '北京', '华北'),
('SH', '上海', '华东'),
('ZJ', '浙江', '华东'),
('JS', '江苏', '华东');

-- ==================== 造 75 条抖音订单（事实表只存维度外键） ====================
INSERT INTO orders (order_id, platform_code, shop_id, buyer_uid, product_id, channel, province, total_amount, pay_status, create_time)
SELECT
    CONCAT('DY', DATE_FORMAT(NOW(), '%Y%m%d'), LPAD(seq, 6, '0')),
    'DOUYIN',
    'insta360_official',
    CONCAT('buyer_', LPAD(seq, 5, '0')),
    CONCAT('p', MOD(seq, 3)),
    CASE MOD(seq, 3) WHEN 0 THEN 'app' WHEN 1 THEN 'web' ELSE 'miniapp' END,
    CASE MOD(seq, 5) WHEN 0 THEN 'GD' WHEN 1 THEN 'BJ' WHEN 2 THEN 'SH' WHEN 3 THEN 'ZJ' ELSE 'JS' END,
    ROUND(2999 + RAND() * 5000, 2),
    CASE WHEN MOD(seq, 10) = 0 THEN 'UNPAID' ELSE 'PAID' END,
    DATE_SUB(NOW(), INTERVAL MOD(seq, 7) DAY)
FROM (
    SELECT @row := @row + 1 AS seq
    FROM (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) t1,
         (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) t2,
         (SELECT 0 UNION ALL SELECT 2 UNION ALL SELECT 3) t3,
         (SELECT @row := 0) t4
) nums
WHERE seq <= 75;
