-- 修复维度表中文双重编码：用 utf8mb4 字符集重新写入（覆盖 mojibake）
USE cdc_source;

INSERT INTO dim_channel (channel_code, channel_name) VALUES
('app', 'APP'), ('web', '网页'), ('miniapp', '小程序')
ON DUPLICATE KEY UPDATE channel_name = VALUES(channel_name);

INSERT INTO dim_province (province_code, province_name, region) VALUES
('GD', '广东', '华南'), ('BJ', '北京', '华北'), ('SH', '上海', '华东'), ('ZJ', '浙江', '华东'), ('JS', '江苏', '华东')
ON DUPLICATE KEY UPDATE province_name = VALUES(province_name), region = VALUES(region);

INSERT INTO dim_product (product_id, product_name, category_name) VALUES
('p0', '机械键盘', '电脑外设'), ('p1', '4K显示器', '电脑外设'), ('p2', '人体工学椅', '办公家具')
ON DUPLICATE KEY UPDATE product_name = VALUES(product_name), category_name = VALUES(category_name);
