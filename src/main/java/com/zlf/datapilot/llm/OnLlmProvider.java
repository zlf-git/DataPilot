package com.zlf.datapilot.llm;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

/**
 * 按 {@code datapilot.llm.provider} 装配桩（Ticket 08b）。
 *
 * <p>替掉原来的 {@code @ConditionalOnProperty(havingValue="stub", matchIfMissing=true)}：
 * 后者只把「<b>属性整行缺失</b>」当缺省，认不出「属性存在但为空串」（yml 里写成 `provider:` 后面留空）——
 * 那种情况容器里一个 {@code LlmClient} 都没有，`AgentEngine` 注入失败，报错还只说
 * `required a bean of type 'LlmClient'`，完全不提配置名（QA-08 P2-1）。
 *
 * <p>这里与 {@code LlmClientFactory}、{@code /api/health/llm} 共用同一条规则：
 * <b>空值/空白/缺失 = stub</b>，非法值（如 {@code qwem}）仍然启动即失败（由 {@code RealLlmProviderCondition}
 * 与工厂的 {@code LlmProvider.find} 兜）。
 */
public abstract class OnLlmProvider implements Condition {

    /** 不写 provider 时的默认值（与 {@code DatapilotProperties.Llm#provider} 初值一致） */
    public static final String DEFAULT_PROVIDER = "stub";

    /** 空值/空白/缺失 → {@code stub}；其余去空白转小写 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PROVIDER;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    protected abstract String expected();

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String raw = context.getEnvironment().getProperty("datapilot.llm.provider");
        return expected().equals(normalize(raw));
    }

    /** {@code provider=stub} 或留空时装配离线桩 */
    public static class Stub extends OnLlmProvider {
        @Override
        protected String expected() {
            return DEFAULT_PROVIDER;
        }
    }
}
