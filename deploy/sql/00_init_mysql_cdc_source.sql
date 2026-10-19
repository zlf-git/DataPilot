-- CDC 源表重建：扩充维度字段，支撑 ODS→DWD→DWS→ADS 完整四层
-- 字段对齐 StreamAsk 完整链路的 ODS 需求（含 product/channel/province 维度）

DROP DATABASE IF EXISTS cdc_source;
CREATE DATABASE cdc_source CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE cdc_source;

CREATE TABLE orders (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        VARCHAR(64) NOT NULL COMMENT '平台订单号',
    platform_code   VARCHAR(32) DEFAULT 'DOUYIN' COMMENT '平台编码',
    shop_id         VARCHAR(32) DEFAULT 'insta360_official' COMMENT '店铺ID',
    buyer_uid       VARCHAR(64) DEFAULT '' COMMENT '买家ID',
    product_id      VARCHAR(64) DEFAULT '' COMMENT '商品ID',
    product_name    VARCHAR(128) DEFAULT '' COMMENT '商品名',
    category_name   VARCHAR(64) DEFAULT '' COMMENT '类目名',
    channel         VARCHAR(32) DEFAULT 'app' COMMENT '渠道',
    province        VARCHAR(64) DEFAULT '广东' COMMENT '省份',
    total_amount    DECIMAL(16,2) DEFAULT 0 COMMENT '订单总金额',
    pay_status      VARCHAR(32) DEFAULT 'PAID' COMMENT '支付状态',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_order_id (order_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 造 75 条抖音订单（含完整维度字段），作为 CDC 初始快照
INSERT INTO orders (order_id, platform_code, shop_id, buyer_uid, product_id, product_name, category_name, channel, province, total_amount, pay_status, create_time)
SELECT
    CONCAT('DY', DATE_FORMAT(NOW(), '%Y%m%d'), LPAD(seq, 6, '0')),
    'DOUYIN',
    'insta360_official',
    CONCAT('buyer_', LPAD(seq, 5, '0')),
    CONCAT('p', MOD(seq, 3) + 1),
    CASE MOD(seq, 3) WHEN 0 THEN '机械键盘' WHEN 1 THEN '4K显示器' ELSE '人体工学椅' END,
    CASE MOD(seq, 3) WHEN 2 THEN '办公家具' ELSE '电脑外设' END,
    CASE MOD(seq, 3) WHEN 0 THEN 'app' WHEN 1 THEN 'web' ELSE 'miniapp' END,
    CASE MOD(seq, 5) WHEN 0 THEN '广东' WHEN 1 THEN '北京' WHEN 2 THEN '上海' WHEN 3 THEN '浙江' ELSE '江苏' END,
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
