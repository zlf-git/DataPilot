package com.zlf.datapilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlf.datapilot.ir.QueryIr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 桩 LLM：provider=stub 时装配。
 * 读 src/main/resources/stub_ir/q{n}.json（question→IR 映射），按 question 匹配返回固定 IR，
 * 并返回固定 usage（in 500 / out 100）保证 cost>0 可验（Ticket 09 判据）。
 * 开发期用它把「IR→SQL→执行」链路跑通，不依赖真实 API Key。
 */
@Component
@ConditionalOnProperty(name = "datapilot.llm.provider", havingValue = "stub")
public class StubLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(StubLlmClient.class);

    private static final int STUB_PROMPT_TOKENS = 500;
    private static final int STUB_COMPLETION_TOKENS = 100;

    private final List<StubEntry> entries = new ArrayList<>();

    public StubLlmClient(ResourcePatternResolver resourceLoader, ObjectMapper objectMapper) {
        Resource[] resources;
        try {
            resources = resourceLoader.getResources("classpath:stub_ir/*.json");
        } catch (IOException e) {
            log.warn("stub_ir 目录不存在或不可读: {}", e.getMessage());
            resources = new Resource[0];
        }
        for (Resource r : resources) {
            try {
                StubEntry entry = objectMapper.readValue(r.getInputStream(), StubEntry.class);
                entries.add(entry);
                log.debug("加载 stub IR: {} -> {}", r.getFilename(), entry.getQuestion());
            } catch (Exception e) {
                log.warn("stub_ir 文件解析失败 {}: {}", r.getFilename(), e.getMessage());
            }
        }
        log.info("StubLlmClient 就绪，加载 {} 条 stub IR", entries.size());
    }

    @Override
    public LlmGeneration generate(String systemPrompt, String userPrompt) {
        LlmGeneration g = new LlmGeneration();
        g.setPromptTokens(STUB_PROMPT_TOKENS);
        g.setCompletionTokens(STUB_COMPLETION_TOKENS);
        g.setTotalTokens(STUB_PROMPT_TOKENS + STUB_COMPLETION_TOKENS);

        if (userPrompt != null) {
            for (StubEntry e : entries) {
                if (e.getQuestion() != null && userPrompt.contains(e.getQuestion())) {
                    g.setIr(e.getIr());
                    return g;
                }
            }
        }
        // 未命中任何 stub：返回空 IR（metrics 为空），由 AgentEngine 报「无法识别查询意图」
        g.setIr(new QueryIr());
        return g;
    }

    /** stub 文件结构：question + 对应 IR */
    public static class StubEntry {
        private String question;
        private QueryIr ir;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public QueryIr getIr() {
            return ir;
        }

        public void setIr(QueryIr ir) {
            this.ir = ir;
        }
    }
}
