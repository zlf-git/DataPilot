-- ============================================================
-- DataPilot 建表脚本（借鉴 DataAgent 的语义模型思想）
-- 5 张元数据表 + 2 张 AgentOps 表 + 1 张业务样例表
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

-- 5. few-shot 示例表：问题-SQL 示例对（智能体知识，提升 NL2SQL 准确率）
CREATE TABLE IF NOT EXISTS few_shot_example (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    question   VARCHAR(500) NOT NULL COMMENT '示例问题，如 上月各渠道销售额',
    sql        TEXT NOT NULL COMMENT '对应的正确 SQL',
    biz_domain VARCHAR(64) COMMENT '业务域：交易/渠道/商品/用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体知识：few-shot 示例';

-- 6. 订单表（简化版，供 Text2SQL 查询演示）
CREATE TABLE IF NOT EXISTS orders (
    order_id   BIGINT PRIMARY KEY COMMENT '订单ID',
    channel    VARCHAR(32) COMMENT '渠道：01抖音/02天猫/03京东',
    sku_id     BIGINT COMMENT '商品ID',
    amount     DECIMAL(12,2) COMMENT '支付金额',
    status     TINYINT COMMENT '状态：1待支付/2已支付/3已退款',
    order_time DATETIME COMMENT '下单时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表（演示用）';

-- 7. AgentOps 运行记录表：一次问数一条（trace）
CREATE TABLE IF NOT EXISTS agent_run (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id         VARCHAR(64) NOT NULL COMMENT '链路追踪ID，一次ask一个',
    session_id       VARCHAR(64) COMMENT '会话ID（多轮）',
    question         VARCHAR(500) COMMENT '用户原始问题',
    status           VARCHAR(16) COMMENT '运行状态：SUCCESS/FAILED/REJECTED',
    total_latency_ms BIGINT COMMENT '端到端总耗时(ms)',
    total_tokens     INT COMMENT 'LLM 总 token 消耗',
    total_cost       DECIMAL(10,6) COMMENT '单次问数 LLM 总成本(元)，对应 AgentOps 工作流定价',
    final_sql        TEXT COMMENT '最终执行的 SQL',
    error_msg        VARCHAR(500) COMMENT '失败原因',
    start_time       DATETIME COMMENT '开始时间',
    end_time         DATETIME COMMENT '结束时间',
    KEY idx_trace (trace_id),
    KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AgentOps：一次问数运行记录';

-- 8. AgentOps 节点明细表：每个节点一条（span）
CREATE TABLE IF NOT EXISTS agent_step (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id   VARCHAR(64) NOT NULL COMMENT '所属 trace',
    step_name  VARCHAR(64) COMMENT '节点名：rewrite/recall/generate/validate/execute/explain',
    span_type  VARCHAR(16) COMMENT 'span类型：operation(节点)/llm(LLM调用)/sql(SQL执行)/vector(向量召回)',
    step_order INT COMMENT '执行顺序',
    status     VARCHAR(16) COMMENT '节点状态：SUCCESS/FAILED/SKIPPED',
    latency_ms BIGINT COMMENT '节点耗时(ms)',
    input      TEXT COMMENT '节点输入摘要',
    output     TEXT COMMENT '节点输出摘要',
    token_used INT COMMENT '该节点 LLM token 消耗',
    error_msg  VARCHAR(500) COMMENT '节点失败原因',
    start_time DATETIME COMMENT '开始时间',
    end_time   DATETIME COMMENT '结束时间',
    KEY idx_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AgentOps：span 执行明细（对齐 AgentOps span 层级）';
