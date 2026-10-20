-- ============================================================
-- DataPilot 元数据库建表脚本（v2）
-- 元数据 5 表 + 指标层 3 表 + 闸门配置 1 表 + AgentOps 2 表 + 业务样例 1 表
-- ============================================================

-- 1. 语义模型表：每张业务表一行，描述「这张表是什么」
CREATE TABLE IF NOT EXISTS semantic_model (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_name    VARCHAR(128) NOT NULL COMMENT '表名',
    table_comment VARCHAR(255) COMMENT '表注释/业务含义',
    biz_domain    VARCHAR(64)  COMMENT '业务域：交易/渠道/地域/商品',
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
    field_role    VARCHAR(16)  COMMENT '角色：dimension维度/metric指标',
    enum_values   JSON COMMENT '枚举值，如 ["APP","小程序","网页"]',
    UNIQUE KEY uk_table_field (table_name, field_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段元数据：字段注释与枚举';

-- 3. 指标口径表（v2，业务部门自助维护）：每个指标一行，口径只定义一次
CREATE TABLE IF NOT EXISTS metric_def (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric_id    VARCHAR(64)  NOT NULL COMMENT '指标唯一ID，如 gmv_total',
    metric_name  VARCHAR(128) NOT NULL COMMENT '指标中文名，如 总GMV',
    metric_alias VARCHAR(255) COMMENT '同义词，逗号分隔，如 总成交额,总交易额',
    department   VARCHAR(64)  COMMENT '归属部门',
    metric_sql   VARCHAR(500) COMMENT '口径SQL，如 SUM(total_gmv)',
    metric_type  VARCHAR(16)  COMMENT '指标类型：simple简单/composite复合/uv去重',
    table_name   VARCHAR(128) COMMENT '主关联表（多表走 metric_table）',
    dimensions   VARCHAR(255) COMMENT '可用维度字段名，逗号分隔，如 dt,channel_name',
    owner        VARCHAR(64)  COMMENT '指标负责人',
    status       VARCHAR(16)  DEFAULT '启用' COMMENT '启用/停用',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_metric_id (metric_id),
    UNIQUE KEY uk_metric_name (metric_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标口径：口径只定义一次';

-- 4. 指标-表关联表：一个指标可挂在多张事实表（如支付订单数在 trade/channel/region 都可算）
CREATE TABLE IF NOT EXISTS metric_table (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric_id  VARCHAR(64)  NOT NULL COMMENT '指标ID',
    table_name VARCHAR(128) NOT NULL COMMENT '该指标可用的事实表',
    UNIQUE KEY uk_metric_table (metric_id, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标-表关联：一指标多表';

-- 5. 维度字典表：业务维度 → 字段映射，供存在性闸门比对
CREATE TABLE IF NOT EXISTS dimension_def (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    dim_name    VARCHAR(64)  NOT NULL COMMENT '维度业务名，如 渠道',
    field_name  VARCHAR(128) NOT NULL COMMENT '字段名，如 channel_name',
    table_name  VARCHAR(128) NOT NULL COMMENT '所属表',
    dim_type    VARCHAR(16)  COMMENT '维度类型：enum枚举/time时间',
    enum_values JSON COMMENT '枚举值列表',
    UNIQUE KEY uk_dim (dim_name, field_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维度字典：业务维度到字段的映射';

-- 6. 业务术语同义词表：黑话 -> 字段映射
CREATE TABLE IF NOT EXISTS term_dict (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    term      VARCHAR(128) NOT NULL COMMENT '业务黑话，如 爆品/成交额/抖音',
    field_ref VARCHAR(255) COMMENT '映射到的字段/条件，如 channel=02',
    UNIQUE KEY uk_term (term)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务术语同义词';

-- 7. few-shot 示例表：问题-SQL 示例对（智能体知识，提升 NL2SQL 准确率）
CREATE TABLE IF NOT EXISTS few_shot_example (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    question   VARCHAR(500) NOT NULL COMMENT '示例问题',
    `sql`      TEXT NOT NULL COMMENT '对应的正确 SQL',
    biz_domain VARCHAR(64) COMMENT '业务域：交易/渠道/地域/商品',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体知识：few-shot 示例';

-- 8. 闸门配置表：代价/结果自检的阈值参数（管理员可调，业务不可见）
CREATE TABLE IF NOT EXISTS gate_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    gate_name   VARCHAR(64) NOT NULL COMMENT '闸门名：cost代价/result结果自检',
    param_key   VARCHAR(64) NOT NULL COMMENT '参数键：max_join/max_metric/max_limit/max_rows',
    param_value VARCHAR(64) NOT NULL COMMENT '参数值',
    updated_by  VARCHAR(64) COMMENT '修改人',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gate_param (gate_name, param_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='闸门配置：阈值参数';

-- 9. 订单表（简化版，供 Text2SQL smoke test，非评测源）
CREATE TABLE IF NOT EXISTS orders (
    order_id   BIGINT PRIMARY KEY COMMENT '订单ID',
    channel    VARCHAR(32) COMMENT '渠道：01抖音/02天猫/03京东',
    sku_id     BIGINT COMMENT '商品ID',
    amount     DECIMAL(12,2) COMMENT '支付金额',
    status     TINYINT COMMENT '状态：1待支付/2已支付/3已退款',
    order_time DATETIME COMMENT '下单时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表（smoke test 用，非评测源）';

-- 10. AgentOps 运行记录表：一次问数一条（trace）
CREATE TABLE IF NOT EXISTS agent_run (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id         VARCHAR(64) NOT NULL COMMENT '链路追踪ID，一次ask一个',
    session_id       VARCHAR(64) COMMENT '会话ID（多轮）',
    question         VARCHAR(500) COMMENT '用户原始问题',
    status           VARCHAR(16) COMMENT '运行状态：SUCCESS/FAILED/REJECTED/PREVIEW',
    total_latency_ms BIGINT COMMENT '端到端总耗时(ms)',
    total_tokens     INT COMMENT 'LLM 总 token 消耗',
    total_cost       DECIMAL(10,6) COMMENT '单次问数 LLM 总成本(元)',
    final_sql        TEXT COMMENT '最终执行的 SQL',
    error_msg        VARCHAR(500) COMMENT '失败原因',
    start_time       DATETIME COMMENT '开始时间',
    end_time         DATETIME COMMENT '结束时间',
    KEY idx_trace (trace_id),
    KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AgentOps：一次问数运行记录';

-- 11. AgentOps 节点明细表：每个节点一条（span）
CREATE TABLE IF NOT EXISTS agent_step (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id   VARCHAR(64) NOT NULL COMMENT '所属 trace',
    step_name  VARCHAR(64) COMMENT '节点名：rewrite/recall/generate/validate/execute/explain/hitl',
    span_type  VARCHAR(16) COMMENT 'span类型：operation/llm/sql/vector/hitl',
    step_order INT COMMENT '执行顺序',
    status     VARCHAR(16) COMMENT '节点状态：SUCCESS/FAILED/SKIPPED/REPLAN',
    latency_ms BIGINT COMMENT '节点耗时(ms)',
    input      TEXT COMMENT '节点输入摘要',
    output     TEXT COMMENT '节点输出摘要',
    token_used INT COMMENT '该节点 LLM token 消耗',
    error_msg  VARCHAR(500) COMMENT '节点失败原因',
    start_time DATETIME COMMENT '开始时间',
    end_time   DATETIME COMMENT '结束时间',
    KEY idx_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AgentOps：span 执行明细';
