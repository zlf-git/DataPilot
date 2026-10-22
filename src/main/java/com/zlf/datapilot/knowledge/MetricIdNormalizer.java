package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 指标 ID 词序归一（Ticket 20）。
 *
 * <p><b>为什么需要</b>：切真实 LLM 后实测，模型对长 metricId 的记忆不稳定，会把
 * {@code gmv_channel_daily_avg} 写成 {@code gmv_channel_avg_daily}（daily 与 avg 顺序颠倒）。
 * 这类错误被存在性闸门整题拒绝，用户看到的是「指标不存在」，连本来能答的部分也一起丢掉。
 *
 * <p><b>策略刻意收得极窄</b>：只纠正「同一组词、只是顺序不同」的情形 ——
 * 把 ID 按 {@code _} 拆成词集合，词集合完全相等且候选唯一时才替换。
 * <ul>
 *   <li>{@code gmv_channel_avg_daily} → 词集 {gmv,channel,avg,daily} 唯一对应
 *       {@code gmv_channel_daily_avg} → 纠正</li>
 *   <li>{@code gmv} → 词集 {gmv} 在白名单里找不到单词 ID → <b>不纠正</b>，
 *       原样交给闸门拒绝</li>
 * </ul>
 *
 * <p><b>绝不做的三件事</b>：不比编辑距离、不做前缀/子串匹配、不做同义词替换。
 * 那三种都会把 {@code gmv} 猜成 {@code gmv_total} 或 {@code gmv_channel}，
 * 属于「模型没说，系统替它编一个」——伪造语义比拒绝回答更危险。
 * 本项目「绝不猜一个答」的约束（QA-03 P2-1）在这里继续成立。
 *
 * <p>纠正结果必须可审计：每次替换都打 warn 日志，写清「原名 → 纠正名」，
 * 便于回放 trace 时区分「模型本来选对了」和「系统替它纠正了」。
 */
@Component
public class MetricIdNormalizer {

    private static final Logger log = LoggerFactory.getLogger(MetricIdNormalizer.class);

    private final OntologyCache cache;

    public MetricIdNormalizer(OntologyCache cache) {
        this.cache = cache;
    }

    /**
     * 原地归一 IR 里的指标 ID，返回被纠正的记录（空表示没有改动）。
     *
     * @return 形如 {@code ["gmv_channel_avg_daily -> gmv_channel_daily_avg"]} 的说明列表
     */
    public List<String> normalize(QueryIr ir) {
        List<String> corrections = new ArrayList<>();
        if (ir == null || ir.getMetrics() == null || ir.getMetrics().isEmpty()) {
            return corrections;
        }
        Set<String> whitelist = cache.metricIds();
        if (whitelist == null || whitelist.isEmpty()) {
            return corrections;
        }
        for (MetricRef ref : ir.getMetrics()) {
            if (ref == null || ref.getMetricId() == null) {
                continue;
            }
            String raw = ref.getMetricId();
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            String matched = match(normalized, whitelist);

            if (matched != null) {
                // 命中白名单：以白名单里的规范写法为准（同时消掉大写与首尾空白的脏值）
                if (!matched.equals(raw)) {
                    ref.setMetricId(matched);
                    if (!matched.equals(normalized)) {
                        // 只有「词序被改」才算纠正，改大小写不算
                        String note = normalized + " -> " + matched;
                        corrections.add(note);
                        log.warn("指标 ID 词序归一：{}（模型输出的 ID 不在白名单，按同词集唯一候选纠正；"
                                + "若这是语义误选，闸门不会再拦，请回放 trace 复核）", note);
                    }
                }
            } else if (!normalized.equals(raw)) {
                // 没命中白名单，但至少把脏值归一（大写/空白），仍交闸门拒绝
                ref.setMetricId(normalized);
            }
        }
        return corrections;
    }

    /**
     * 单个 ID 的归一。已在白名单内直接返回；否则按词集找唯一候选。
     * 找不到或多于一个候选都返回 null，表示「不改」。
     */
    private String match(String metricId, Set<String> whitelist) {
        if (whitelist.contains(metricId)) {
            return metricId;
        }
        String sig = signature(metricId);
        if (sig.isEmpty()) {
            return null;
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (String id : whitelist) {
            if (signature(id).equals(sig)) {
                candidates.add(id);
            }
        }
        // 唯一候选才纠正。多于一个说明这组词能拼出多个合法指标（如 a_b_c 与 a_c_b 都在库里），
        // 系统没有依据选谁，交回闸门拒绝。
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /** 词集签名：按 {@code _} 拆词、去掉空段、排序后拼回。{@code a_b_c} 与 {@code a_c_b} 得到同一签名 */
    private static String signature(String id) {
        if (id == null) {
            return "";
        }
        List<String> words = new ArrayList<>();
        for (String part : id.toLowerCase(Locale.ROOT).split("_")) {
            if (!part.isBlank()) {
                words.add(part);
            }
        }
        if (words.isEmpty()) {
            return "";
        }
        words.sort(String::compareTo);
        return String.join("_", words);
    }
}
