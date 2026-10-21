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
import java.util.Comparator;
import java.util.List;

/**
 * 桩 LLM：provider=stub 时装配。
 * 读 src/main/resources/stub_ir/q{n}.json（question→IR 映射），按 question 匹配返回固定 IR，
 * 并返回固定 usage（in 500 / out 100）保证 cost>0 可验（Ticket 09 判据）。
 * 开发期用它把「IR→SQL→执行」链路跑通，不依赖真实 API Key。
 *
 * <p>匹配走归一化：去空白/标点、「号」→「日」、全角转半角，避免评测集措辞差异
 * （"10月19号" vs "10月19日"、多空格、尾问号）导致全线「无法识别」。
 */
@Component
@ConditionalOnProperty(name = "datapilot.llm.provider", havingValue = "stub")
public class StubLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(StubLlmClient.class);

    private static final int STUB_PROMPT_TOKENS = 500;
    private static final int STUB_COMPLETION_TOKENS = 100;

    private final List<StubEntry> entries = new ArrayList<>();
    private final ObjectMapper objectMapper;

    public StubLlmClient(ResourcePatternResolver resourceLoader, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
                entry.normalizedQuestion = normalize(entry.getQuestion());
                entries.add(entry);
                log.debug("加载 stub IR: {} -> {}", r.getFilename(), entry.getQuestion());
            } catch (Exception e) {
                log.warn("stub_ir 文件解析失败 {}: {}", r.getFilename(), e.getMessage());
            }
        }
        // 按归一化后 question 长度降序：长题优先，避免短 question 抢先命中
        entries.sort(Comparator.comparingInt((StubEntry e) -> e.normalizedQuestion.length()).reversed());
        log.info("StubLlmClient 就绪，加载 {} 条 stub IR", entries.size());
    }

    @Override
    public LlmGeneration generate(String systemPrompt, String userPrompt) {
        LlmGeneration g = new LlmGeneration();
        g.setPromptTokens(STUB_PROMPT_TOKENS);
        g.setCompletionTokens(STUB_COMPLETION_TOKENS);
        g.setTotalTokens(STUB_PROMPT_TOKENS + STUB_COMPLETION_TOKENS);

        if (userPrompt != null) {
            // 只拿「真实问题」那一行做匹配，不对整段 prompt 做 contains：
            // prompt 里还有指标清单/DDL/few-shot，few-shot 的示例问题会和真问题撞车
            // （Ticket 03 踩过：注入的 few-shot 就是 Q1 原题，导致所有问题都命中 Q1）。
            String normPrompt = normalize(extractQuestion(userPrompt));
            StubEntry picked = match(normPrompt);
            if (picked != null) {
                g.setIr(deepCopy(picked.getIr()));
                return g;
            }
        }
        // 未命中（或命中多题 = 多意向拼接，桩拆不开）：返回空 IR，
        // 由 AgentEngine 统一报「无法识别查询意图」，绝不猜一个答 —— QA-03 P2-1
        g.setIr(new QueryIr());
        return g;
    }

    /**
     * 匹配策略（QA-03 P2-1）：精确优先，其次唯一部分匹配；部分匹配到<b>多条</b>说明用户把两个问题拼在一起问了
     * （「近7天GMV最高的渠道是哪个？另外10月19日总GMV是多少」），此前会静默只答第一题、第二题彻底消失。
     * 桩没有拆句能力，这里显式放弃（走「无法识别」），把「不能静默丢问题」这条约束钉住。
     */
    private StubEntry match(String normPrompt) {
        if (normPrompt.isEmpty()) {
            return null;
        }
        List<StubEntry> partial = new ArrayList<>();
        for (StubEntry e : entries) {
            if (e.normalizedQuestion.isEmpty()) {
                continue;
            }
            if (normPrompt.equals(e.normalizedQuestion)) {
                return e;
            }
            if (normPrompt.contains(e.normalizedQuestion)) {
                partial.add(e);
            }
        }
        if (partial.size() == 1) {
            log.debug("stub 部分匹配：命中「{}」，prompt 归一化后长度 {} > {}",
                    partial.get(0).getQuestion(), normPrompt.length(), partial.get(0).normalizedQuestion.length());
            return partial.get(0);
        }
        if (partial.size() > 1) {
            log.warn("提问疑似包含 {} 个意图（命中 {}），桩无法拆句，按「无法识别」处理，请一次只问一个问题",
                    partial.size(), partial.stream().map(StubEntry::getQuestion).toList());
        }
        return null;
    }

    /**
     * 从 prompt 里抽出「真实问题」：PromptBuildStep 的第一行固定是 {@code 问题：<question>}，
     * 后面才是指标清单/DDL/few-shot。取第一处「问题：」到行尾，避免 few-shot 示例问题污染匹配。
     *
     * <p>抽到的内容为空（空串/纯空白问题）时返回<b>空串</b>，不能回落到整段 prompt：
     * prompt 里的 few-shot 正文就是 Q1 原题，回落等于「空白问题 → 返回 Q1 的 53042.41」，
     * 一条看着完全正常的错数（QA-03 P1-1）。空串匹配不到任何非空 stub，自然落到「无法识别」。
     */
    private static String extractQuestion(String prompt) {
        int idx = prompt.indexOf("问题：");
        if (idx < 0) {
            return prompt;
        }
        int start = idx + "问题：".length();
        int end = prompt.indexOf('\n', start);
        String q = end > 0 ? prompt.substring(start, end) : prompt.substring(start);
        return q.trim();
    }

    /**
     * 深拷贝后返回：entries 里的 IR 是单例，直接交给下游会被 03/05 就地补字段（selectMode/timeRange）
     * 时跨请求串数据（01c 测试报告 P2-3）。拷贝失败才退化为返回原实例。
     */
    private QueryIr deepCopy(QueryIr ir) {
        if (ir == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(ir, QueryIr.class);
        } catch (Exception e) {
            log.warn("stub IR 深拷贝失败，退化为共享实例：{}", e.getMessage());
            return ir;
        }
    }

    /** 归一化：去空白/标点、「号」→「日」、全角转半角、转小写，用于模糊匹配 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u3000') { // 全角空格
                c = ' ';
            } else if (c >= '\uFF01' && c <= '\uFF5E') { // 全角字符转半角
                c = (char) (c - 0xFEE0);
            }
            if (c == '号') {
                c = '日';
            }
            if (Character.isWhitespace(c) || isPunct(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 标点判断：半角 + 中文特有标点（全角标点已在 normalize 转半角，此处兜底重复列出） */
    private static boolean isPunct(char c) {
        switch (c) {
            case ',': case '.': case ';': case ':': case '?': case '!':
            case '"': case '\'': case '(': case ')': case '[': case ']':
            case '{': case '}': case '<': case '>':
            case '。': case '、': case '“': case '”': case '‘': case '’':
            case '【': case '】': case '《': case '》': case '·': case '—': case '…':
                return true;
            default:
                return false;
        }
    }

    /** stub 文件结构：question + 对应 IR */
    public static class StubEntry {
        private String question;
        private QueryIr ir;
        /** 归一化后的 question（运行时计算，不参与 JSON 反序列化） */
        private String normalizedQuestion = "";

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
