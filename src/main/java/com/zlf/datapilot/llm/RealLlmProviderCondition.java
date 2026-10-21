package com.zlf.datapilot.llm;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@code datapilot.llm.provider} 是真实 provider 时才装配工厂产物（Ticket 08）。
 *
 * <p>为什么不直接用 {@code @ConditionalOnProperty(havingValue="deepseek")}：
 * 它只接受单个值，两个 provider 就得写两个 {@code @Bean} 方法（qwen 那份将来还得跟着加），
 * 而「哪些值算真实 provider」本来就该由 {@link LlmProvider} 一处定义。
 *
 * <p>这里判定为 false 的分支只有 {@code stub}/{空}：此时容器里只有 Ticket 01 的
 * {@link StubLlmClient}，二者<b>绝不会同时存在</b>（否则 {@code LlmClient} 注入会 ambiguity）。
 *
 * <p>非法值（如 {@code deepseek-v3}、{@code qwem}）在这里就抛：Spring 会把它包成
 * Bean 创建失败，启动直接失败并带上可选值 —— 与 Ticket 07 的 {@code engine.type} 同款。
 * 反过来如果这里静默返回 false，应用能正常启动，但没有任何 {@code LlmClient} bean，
 * 用户问第一个问题时才报 NoSuchBeanDefinition，排查成本高得多。
 */
public class RealLlmProviderCondition implements Condition {

    static final String PROVIDER_KEY = "datapilot.llm.provider";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String raw = context.getEnvironment().getProperty(PROVIDER_KEY, "stub");
        return LlmProvider.find(raw).isPresent();
    }
}
