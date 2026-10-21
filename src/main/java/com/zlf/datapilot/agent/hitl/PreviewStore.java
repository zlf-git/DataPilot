package com.zlf.datapilot.agent.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.ir.QueryIr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 预览快照存储（Ticket 12）：preview 与 execute 之间的服务端状态。
 *
 * <p><b>为什么不把 IR 交回客户端、让 execute 再送回来</b>：那样「预览时校验过什么」就没有意义了
 * —— 客户端可以换一份完全不同的 IR 来执行。快照留在服务端，execute 只接受「对快照的增量编辑」，
 * 编辑的合法性由 {@link IrEditGuard} 在服务端判定；没带编辑就按快照原样执行。
 *
 * <p>IR 以 <b>JSON 文本</b>存：一来序列化天然是深拷贝（execute 改不动快照），
 * 二来落进响应体的「编辑底稿」与执行时解析的是同一份字节，不含隐式的类型漂移。
 * 代价是每次 execute 多一次 JSON 解析（微秒级，与一次 SQL 执行不在一个量级）。
 *
 * <p>三条上限与 {@link com.zlf.datapilot.agent.session.SessionStore} 同思路：
 * TTL（默认 600 秒，按<b>创建</b>时刻算 —— 预览是「即将执行的一次确认」，不该像会话那样续期）、
 * 总量上限（超了淘汰最久未创建的）、进程内存（重启即丢）。
 * TTL 用秒而不是分钟，是为了让「过期」这件事在测试里能被真实地等出来（1 秒），
 * 而不是靠改系统时间或反射把字段拗成过期。
 */
@Component
public class PreviewStore {

    private static final Logger log = LoggerFactory.getLogger(PreviewStore.class);

    /**
     * 一份待确认的查询。
     *
     * @param previewId  opaque id，execute 时用它取回快照
     * @param traceId    预览那次运行的 traceId（execute 的 trace 是另一次运行，靠这个字段串起来）
     * @param irJson     预览时的 IR（JSON 文本，深拷贝用）
     * @param sql        预览生成的 SQL（<b>只用于展示</b>；execute 一定重新生成，不复用这份文本）
     * @param gates      预览时四道闸门的执行结果（回显用）
     * @param comparisonDays 多轮「那环比呢」的天数（可空）。<b>必须跟着快照走</b>：
     *                   它不在 IR 里（是 RewriteStep 就地在上下文上标的），
     *                   丢了它就会出现「预览说这是环比问题、执行时却只出本期」的半截响应
     */
    public record Preview(String previewId,
                          String traceId,
                          String question,
                          String sessionId,
                          String rewrittenQuestion,
                          String irJson,
                          String sql,
                          List<Object> params,
                          List<String> gates,
                          Integer comparisonDays,
                          long createdAt,
                          long expiresAt) {
    }

    private final ObjectMapper objectMapper;
    private final int maxPreviews;
    private final long ttlMillis;

    /**
     * accessOrder=true：淘汰时先淘汰<b>最久未被访问</b>的（LRU），不是最久未创建的。
     * 差别是真实的：某个快照被执行过一次（get 刷新访问序）后，它反而更不容易被淘汰 ——
     * QA-12 P3-2 指出原注释写反了，会让下一个人按错误假设推理。
     */
    private final Map<String, Preview> previews = new LinkedHashMap<>(16, 0.75f, true);

    public PreviewStore(ObjectMapper objectMapper,
                        @Value("${datapilot.hitl.max-previews:200}") int maxPreviews,
                        @Value("${datapilot.hitl.preview-ttl-seconds:600}") long ttlSeconds) {
        this.objectMapper = objectMapper;
        this.maxPreviews = Math.max(1, maxPreviews);
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
        log.info("预览快照存储就绪：maxPreviews={} ttl={}s（进程内存，重启即失效）",
                this.maxPreviews, ttlSeconds);
    }

    /** 把一次预览成功的运行存成快照，返回带 previewId 的快照 */
    public Preview save(AgentContext ctx) {
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        Preview preview = new Preview(id,
                ctx.getTraceId(),
                ctx.getUserQuestion(),
                ctx.getSessionId(),
                ctx.getRewrittenQuestion(),
                toJson(ctx.getIr()),
                ctx.getGeneratedSql(),
                ctx.getSqlCommand() == null ? List.of() : List.copyOf(ctx.getSqlCommand().params()),
                List.copyOf(ctx.getGateResults()),
                ctx.getComparisonDays(),
                now,
                now + ttlMillis);
        synchronized (previews) {
            purgeExpired(System.currentTimeMillis());
            if (previews.size() >= maxPreviews) {
                String eldest = previews.keySet().iterator().next();
                previews.remove(eldest);
                log.debug("预览快照达上限 {}，淘汰最久未访问的 {}", maxPreviews, eldest);
            }
            previews.put(id, preview);
        }
        return preview;
    }

    /** 取快照；不存在或已过期返回 null（调用方按「请重新预览」处理，不做任何猜测） */
    public Preview get(String previewId) {
        if (previewId == null || previewId.isBlank()) {
            return null;
        }
        synchronized (previews) {
            Preview p = previews.get(previewId);
            if (p == null) {
                return null;
            }
            if (System.currentTimeMillis() > p.expiresAt()) {
                previews.remove(previewId);
                log.debug("预览快照 {} 已超过 TTL，失效", previewId);
                return null;
            }
            return p;
        }
    }

    /** 把快照里的 IR 解析成一份<b>全新的</b>对象（调用方可以随便改，改不到快照） */
    public QueryIr parseIr(Preview preview) {
        try {
            return objectMapper.readValue(preview.irJson(), QueryIr.class);
        } catch (JsonProcessingException e) {
            // 只可能是自身写入的内容解析不了（版本升级/字段改名），属于内部不一致，不该静默
            throw new IllegalStateException("预览快照的 IR 无法解析：" + e.getOriginalMessage(), e);
        }
    }

    /** 当前存活快照数（健康检查/测试用） */
    public int count() {
        synchronized (previews) {
            purgeExpired(System.currentTimeMillis());
            return previews.size();
        }
    }

    public int maxPreviews() {
        return maxPreviews;
    }

    public long ttlSeconds() {
        return ttlMillis / 1000L;
    }

    /** 展示用：不足一分钟按一分钟算（消息里说「0 分钟有效」很怪） */
    public long ttlMinutes() {
        return Math.max(1, (ttlMillis + 59_999L) / 60_000L);
    }

    /**
     * 给用户看的有效期文案（QA-12 P3-1）。
     *
     * <p>只按分钟向上取整会在自定义短 TTL 时<b>夸大</b>有效期：配 1 秒实际 1 秒后失效，
     * 提示却说「1 分钟有效」。所以不足 60 秒直接说秒数，别为了句子好看去改事实。
     */
    public String ttlText() {
        long seconds = ttlSeconds();
        return seconds >= 60 ? ttlMinutes() + " 分钟" : seconds + " 秒";
    }

    /** 仅供测试：清空 */
    public void clear() {
        synchronized (previews) {
            previews.clear();
        }
    }

    private void purgeExpired(long now) {
        previews.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }

    private String toJson(QueryIr ir) {
        try {
            return objectMapper.writeValueAsString(ir);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("预览快照的 IR 无法序列化：" + e.getOriginalMessage(), e);
        }
    }

    /** 空列表兜底（响应体里 params/gates 不该出现 null） */
    public static List<Object> emptyIfNull(List<Object> v) {
        return v == null ? new ArrayList<>() : v;
    }
}
