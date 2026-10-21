package com.zlf.datapilot.controller;

import com.zlf.datapilot.agent.hitl.PreviewStore;
import com.zlf.datapilot.agent.session.SessionStore;
import com.zlf.datapilot.agent.trace.SpanType;
import com.zlf.datapilot.agent.trace.TraceSpan;
import com.zlf.datapilot.knowledge.EngineWhitelist;
import com.zlf.datapilot.llm.LlmClient;
import com.zlf.datapilot.llm.LlmDescriptor;
import com.zlf.datapilot.service.MetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康检查接口。
 * <ul>
 *   <li>{@code GET /api/health/metadata}：指标/字段/闸门参数条数（Ticket 01b），供运维探活与启动自检。</li>
 *   <li>{@code GET /api/health/engine}：当前生效的执行引擎 + 可读表白名单自检（Ticket 07），
 *       切引擎后一眼能看出「是否真的换过来了」。</li>
 *   <li>{@code GET /api/health/llm}：当前生效的 LLM provider / base-url / model / Key 是否读到（Ticket 08），
 *       切模型后同样一眼可确认；<b>不回显 Key 值</b>。</li>
 *   <li>{@code GET /api/health/traces}：埋点 span 目录与「一次成功运行该有多少 span」（Ticket 09），
 *       e2e 脚本读它当基线，不写死数字。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final MetadataService service;
    private final EngineWhitelist engineWhitelist;
    private final LlmClient llmClient;
    private final SessionStore sessions;
    private final PreviewStore previews;

    public HealthController(MetadataService service, EngineWhitelist engineWhitelist,
                            LlmClient llmClient, SessionStore sessions, PreviewStore previews) {
        this.service = service;
        this.engineWhitelist = engineWhitelist;
        this.llmClient = llmClient;
        this.sessions = sessions;
        this.previews = previews;
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadataHealth() {
        return service.metadataHealth();
    }

    @GetMapping("/engine")
    public Map<String, Object> engineHealth() {
        return engineWhitelist.report();
    }

    @GetMapping("/llm")
    public Map<String, Object> llmHealth() {
        // QA-08 P2-2：原来这里有个 `client == null` 分支，但根本没有「没有 LlmClient」的可用状态 ——
        // AgentEngine 强依赖它，缺 bean 时应用起不到能响应 HTTP 的地步（stub 已用 matchIfMissing 兜底）。
        // 「宣称有、实际不生效」的分支比没有更糟，删掉。
        Map<String, Object> out = new LinkedHashMap<>();
        LlmDescriptor d = llmClient.descriptor();
        out.put("provider", d.provider());
        out.put("baseUrl", d.baseUrl());
        out.put("model", d.model());
        out.put("apiKeyConfigured", d.apiKeyConfigured());
        out.put("temperature", d.temperature());
        out.put("impl", llmClient.getClass().getSimpleName());
        return out;
    }

    /**
     * 埋点目录自检（Ticket 09）：暴露「一次成功运行应产出多少 span」，
     * 让 e2e 脚本读基线而不是写死数字 —— 加节点时这里自动跟着变，脚本不用改。
     */
    @GetMapping("/traces")
    public Map<String, Object> traceHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (TraceSpan span : TraceSpan.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepName", span.code());
            item.put("spanType", span.type().code());
            item.put("inAskBaseline", span.inAskBaseline());
            catalog.add(item);
        }
        out.put("expectedSpansPerSuccess", TraceSpan.expectedPerSuccess());
        out.put("spanCatalog", catalog);
        // Ticket 12：HITL 的 span 单独列出，明确「它不算进 ask 基线」——
        // 否则下一个人加 span 时会以为基线该跟着涨
        out.put("hitlSpans", Arrays.stream(TraceSpan.values())
                .filter(s -> !s.inAskBaseline())
                .map(TraceSpan::code)
                .toList());
        out.put("spanTypes", Arrays.stream(SpanType.values()).map(SpanType::code).toList());
        return out;
    }

    /**
     * 预览快照自检（Ticket 12）：当前存活快照数与 TTL。
     *
     * <p>与 /session 同理：快照是「等人确认」的临时状态，泄漏了会一直占内存又不会被任何查询命中，
     * 所以把「有多少份在等确认」暴露出来，比事后猜要省事。
     */
    @GetMapping("/hitl")
    public Map<String, Object> hitlHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("previews", previews.count());
        out.put("maxPreviews", previews.maxPreviews());
        out.put("ttlSeconds", previews.ttlSeconds());
        out.put("ttlMinutes", previews.ttlMinutes());
        out.put("storage", "memory");
        out.put("editable", List.of("filters[].value", "limit", "orderBy[].direction", "dimensions"));
        return out;
    }

    /**
     * 会话存储自检（Ticket 10）：当前存活会话数与三条上限。
     *
     * <p>会话在进程内存里（单机单用户作品），重启即清空是<b>有意的降级</b>：
     * 每轮写一次元数据库会给主链路加一次 fsync（Ticket 09 为此把延迟从 231ms 拉回 56ms）。
     * 这里暴露上限，是为了让「会话泄漏」这类问题一眼可见，而不是靠猜。
     */
    @GetMapping("/session")
    public Map<String, Object> sessionHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", sessions.sessionCount());
        out.put("maxSessions", sessions.maxSessions());
        out.put("maxRounds", sessions.maxRounds());
        out.put("ttlMinutes", sessions.ttlMinutes());
        out.put("storage", "memory");
        return out;
    }
}
