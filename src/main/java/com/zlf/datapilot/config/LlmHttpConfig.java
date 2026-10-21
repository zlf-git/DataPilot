package com.zlf.datapilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * LLM HTTP 客户端超时配置（Ticket 01 任务 8 补完）。
 * Spring AI 1.0.0 的 OpenAiApi 底层用 RestClient.Builder；此处提供一个带超时的 builder，
 * Spring Boot 自动配置(@ConditionalOnMissingBean) 会用它替代默认无超时的 builder，
 * 从而 DeepSeek 调用获得 connect 3s / read 30s 超时。
 * 没有超时时，网络黑洞（连得上但不回包）会让请求无限挂起，比直接抛错更糟。
 */
@Configuration
public class LlmHttpConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(factory);
    }
}
