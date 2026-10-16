-- ============================================================
-- DataPilot 建表脚本（借鉴 DataAgent 的语义模型思想）
-- 4 张元数据表 + 1 张业务样例表
-- ============================================================

-- 1. 语义模型表：每张业务表一行，描述「这张表是什么」
CREATE TABLE IF NOT EXISTS semantic_model (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_name    VARCHAR(128) NOT NULL COMMENT '表名',
    table_comment VARCHAR(255) COMMENT '表注释/业务含义',
    biz_domain    VARCHAR(64)  COMMENT '业务域：交易/渠道/商品/用户/成本',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_table (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语义模型：表的元数据';

-- 2. 字段元数据表：每个字段一行，含枚举值（NL2SQL 准确率的关键）
CREATE TABLE IF NOT EXISTS field_metadata (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_name    VARCHAR(128) NOT NULL COMMENT '所属表',
    field_name    VARCHAR(128) NOT NULL COMMENT '字段名',
    field_type    VARCHAR(64)  COMMENT '字段类型',
    field_comment VARCHAR(255) COMMENT '字段注释/业务含义',
    enum_values   JSON COMMENT '枚举值，如 [{"code":"1","name":"待支付"}]',
    UNIQUE KEY uk_table_field (table_name, field_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段元数据：字段注释与枚举';

-- 3. 指标口径表：每个指标一行，口径只定义一次
CREATE TABLE IF NOT EXISTS metric_def (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric_name VARCHAR(128) NOT NULL COMMENT '指标中文名，如 支付金额',
    en_name     VARCHAR(128) COMMENT '英文名，如 gmv',
    biz_domain  VARCHAR(64)  COMMENT '业务域',
    definition  VARCHAR(500) COMMENT '口径定义，如 支付金额=SUM(amount) WHERE status=2',
    UNIQUE KEY uk_metric (metric_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标口径：口径只定义一次';

-- 4. 业务术语同义词表：黑话 -> 字段映射
CREATE TABLE IF NOT EXISTS term_dict (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    term      VARCHAR(128) NOT NULL COMMENT '业务黑话，如 爆品/成交额/抖音',
    field_ref VARCHAR(255) COMMENT '映射到的字段/条件，如 channel=02',
    UNIQUE KEY uk_term (term)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务术语同义词';

-- 5. 订单表（简化版，供 Text2SQL 查询演示）
CREATE TABLE IF NOT EXISTS orders (
    order_id   BIGINT PRIMARY KEY COMMENT '订单ID',
    channel    VARCHAR(32) COMMENT '渠道：01抖音/02天猫/03京东',
    sku_id     BIGINT COMMENT '商品ID',
    amount     DECIMAL(12,2) COMMENT '支付金额',
    status     TINYINT COMMENT '状态：1待支付/2已支付/3已退款',
    order_time DATETIME COMMENT '下单时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表（演示用）';
