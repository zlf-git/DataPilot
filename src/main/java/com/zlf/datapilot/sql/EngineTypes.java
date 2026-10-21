package com.zlf.datapilot.sql;

import java.util.Locale;
import java.util.Set;

/**
 * 引擎类型的规范化与合法集合（Ticket 07b 起唯一真源）。
 *
 * <p>存在理由：`EngineJdbc`（构造期）与执行器的装配条件（容器期）必须对「type 取什么值算 starrocks」
 * 给同一答案。此前两边各写一套 —— 构造器把空串当归 starrocks，`@ConditionalOnProperty` 只认缺失
 * 或字面量 `starrocks`，于是 `--datapilot.engine.type=`（属性存在但空串）会出现
 * 「构造器说 starrocks、容器里两个执行器都不装配」，启动失败且报错不提配置名（QA-07 P2-2 / QA-08 P2-3）。
 */
public final class EngineTypes {

    public static final String STARROCKS = "starrocks";
    public static final String MYSQL = "mysql";

    /** 不写 type 时的引擎（与 application.yml 注释、StarRocks 声明口径一致） */
    public static final String DEFAULT_TYPE = STARROCKS;

    public static final Set<String> SUPPORTED = Set.of(STARROCKS, MYSQL);

    private EngineTypes() {
    }

    /**
     * 空值/空白/缺行 → {@link #DEFAULT_TYPE}；其余去空白转小写。
     * 三个入口（{@link EngineJdbc} 构造、{@link OnEngineType} 条件、健康检查）共用这一处。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TYPE;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
