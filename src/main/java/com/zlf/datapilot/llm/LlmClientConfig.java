package com.zlf.datapilot.llm;

import com.zlf.datapilot.config.DatapilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.client.RestClient;

/**
 * 真实 LLM 客户端的装配（Ticket 08）。
 *
 * <p>装配矩阵（互斥，不存在两个 {@code LlmClient} bean）：
 * <table border="1">
 *   <caption>provider 与实现</caption>
 *   <tr><th>datapilot.llm.provider</th><th>实现</th><th>装配方</th></tr>
 *   <tr><td>stub（默认）</td><td>StubLlmClient</td><td>Ticket 01 的 @ConditionalOnProperty</td></tr>
 *   <tr><td>deepseek / qwen</td><td>OpenAiCompatibleLlmClient</td><td>本类（经 LlmClientFactory）</td></tr>
 * </table>
 *
 * <p>工厂（{@link LlmClientFactory}）始终是个 bean，但<b>只有</b> provider 是真实值时才被用来产
 * {@code LlmClient}：这既保证「切 provider 只改配置」，也让「工厂能产 stub」这条歧路在编译期就不成立。
 *
 * <p>启动时会打一行「LLM 就绪：provider=... base-url=... model=... key=已配置/缺失」，
 * 与 {@code GET /api/health/llm} 同源，避免配了 provider 却因环境变量没导而静默走默认值。
 */
@Configuration
public class LlmClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmClientConfig.class);

    /**
     * 用 {@code ObjectProvider} 而不是直接注入 {@link LlmClient}：
     * 本类的 {@code llmClient} bean 同时也是启动自检的读取目标，直接构造注入会形成自引用。
     * ObjectProvider 是懒的，provider=stub 时这里读到的就是 Ticket 01 的 StubLlmClient。
     */
    private final ObjectProvider<LlmClient> llmClient;

    public LlmClientConfig(ObjectProvider<LlmClient> llmClient) {
        this.llmClient = llmClient;
    }

    /** 工厂本身随时可用（单测与将来的多模型并存都用它），但它不产 stub */
    @Bean
    public LlmClientFactory llmClientFactory(RestClient.Builder restClientBuilder) {
        return new LlmClientFactory(restClientBuilder);
    }

    /**
     * 真实 provider 的 {@code LlmClient}。provider=stub 时整个方法不参与装配，
     * 容器里只有 StubLlmClient。
     */
    @Bean
    @Conditional(RealLlmProviderCondition.class)
    public LlmClient llmClient(LlmClientFactory factory, DatapilotProperties props) {
        return factory.create(props.getLlm());
    }

    /**
     * 启动自检：一句话说清「现在到底连的是谁」，Key 只报有无不报值。
     *
     * <p>注意方法<b>不带业务参数</b>：{@code @EventListener} 的单参数方法会把第一个参数当事件体传，
     * 写 {@code reportLlm(LlmClient)} 会在 ApplicationReadyEvent 触发时抛
     * {@code argument type mismatch} —— 启动成功后再崩，比启动失败更难查（本轮实际踩到）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportLlm() {
        LlmClient client = llmClient.getIfAvailable();
        if (client == null) {
            log.warn("LLM 未装配：容器里没有 LlmClient bean（检查 datapilot.llm.provider 取值）");
            return;
        }
        LlmDescriptor d = client.descriptor();
        if ("stub".equals(d.provider())) {
            log.info("LLM 就绪：provider=stub（离线桩，读 stub_ir；切真实模型改 datapilot.llm.provider）");
            return;
        }
        log.info("LLM 就绪：provider={} base-url={} model={} key={}",
                d.provider(), d.baseUrl(), d.model(), d.apiKeyConfigured() ? "已配置" : "缺失");
    }
}
