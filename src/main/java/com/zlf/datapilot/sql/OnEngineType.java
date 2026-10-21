package com.zlf.datapilot.sql;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 按 {@code datapilot.engine.type} 装配执行器（Ticket 08b，替掉 {@code @ConditionalOnProperty}）。
 *
 * <p>为什么不用 {@code @ConditionalOnProperty}：它只认「属性缺失」或「字面量相等」，
 * 认不出「属性存在但为空串」这种最常见的「留空即默认」写法 —— 用户删掉值但留着 `type:` 这行，
 * 容器里就一个执行器都装配不上，报错还是 `required a bean of type 'SqlExecutor'`（QA-08 P2-3）。
 * 这里改为与 {@link EngineJdbc} 共用 {@link EngineTypes#normalize(String)}，两者永远同答案。
 */
public abstract class OnEngineType implements Condition {

    /** 本条件期望的引擎类型（规范化后的值） */
    protected abstract String expected();

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String raw = context.getEnvironment().getProperty("datapilot.engine.type");
        return expected().equals(EngineTypes.normalize(raw));
    }

    /** {@code datapilot.engine.type=starrocks}（缺省/留空也算）时装配 StarRocks 执行器 */
    public static class StarRocks extends OnEngineType {
        @Override
        protected String expected() {
            return EngineTypes.STARROCKS;
        }
    }

    /** {@code datapilot.engine.type=mysql} 时装配 MySQL 执行器 */
    public static class Mysql extends OnEngineType {
        @Override
        protected String expected() {
            return EngineTypes.MYSQL;
        }
    }
}
