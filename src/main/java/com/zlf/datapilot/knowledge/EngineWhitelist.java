package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.sql.SqlExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 引擎可读表白名单的「声明 ↔ 元数据」一致性自检（Ticket 07 任务 4 的钩子消费方）。
 *
 * <p>两个白名单来源必须对齐才能问数：
 * <ul>
 *   <li><b>引擎声明</b>：{@link SqlExecutor#readableTables()}，引擎自己说「我这个数据源里哪些表给 Agent 读」；</li>
 *   <li><b>元数据白名单</b>：{@link OntologyCache#tables()}，来自 {@code semantic_model}。</li>
 * </ul>
 * 最终可读集合 = 交集（引擎没声明 = 不额外限制，纯元数据说了算，保持 Ticket 02 的现有行为不变）。
 *
 * <p><b>本类只自检、不拦截</b>：拒绝判定属于 Ticket 02 的存在性/权限闸门，按 07 票面
 * 「实际注册逻辑在 Ticket 02 内完成」不越界。<b>但钩子不是死代码</b>——它在启动时打一行自检结论，
 * 并通过 {@code GET /api/health/engine} 暴露，可以被集成测试与运维直接断言：
 * 切 MySQL 引擎后这个接口必须回报 {@code engine=mysql} 且声明表只剩 {@code orders}。
 *
 * <p>典型失配：元数据里配置了 {@code ads_*} 四张表，但引擎切成 MySQL（库里只有 {@code orders}），
 * 此时 {@code declaredNotInMetadata} 为空、{@code metadataNotDeclared} = 四张 ADS 表 —— 说明「元数据还指着
 * StarRocks 的表」，问数会在执行阶段失败。自检把这层信息提前暴露，而不是等用户问一句才报错。
 */
@Component
public class EngineWhitelist {

    private static final Logger log = LoggerFactory.getLogger(EngineWhitelist.class);

    private final SqlExecutor executor;
    private final OntologyCache cache;

    public EngineWhitelist(SqlExecutor executor, OntologyCache cache) {
        this.executor = executor;
        this.cache = cache;
    }

    /** 最终可读表白名单：引擎没声明就完全听元数据的；声明了就取交集（fail-closed）。 */
    public Set<String> effective() {
        Set<String> declared = executor.readableTables();
        Set<String> metadata = cache.tables();
        if (declared == null || declared.isEmpty()) {
            return metadata;
        }
        Set<String> both = new TreeSet<>(declared);
        both.retainAll(metadata);
        return both;
    }

    /** 单个表是否可读（供 Ticket 02 闸门后续接线的直接入口）。 */
    public boolean isReadable(String table) {
        return table != null && effective().contains(table);
    }

    /** 自检报告，同时用于启动日志与 {@code GET /api/health/engine}。 */
    public Map<String, Object> report() {
        Set<String> declared = sorted(executor.readableTables());
        Set<String> metadata = sorted(cache.tables());
        Set<String> effective = sorted(effective());
        boolean undeclared = declared.isEmpty();

        Set<String> declaredNotInMetadata = new TreeSet<>(declared);
        declaredNotInMetadata.removeAll(metadata);

        // 未声明（引擎不写表清单）= 不额外限制，此时「元数据里的表引擎没声明」不是失配，是设计如此
        // （Ticket 19：StarRocks 侧已不再写死 4 张 ADS 表，加表不用改代码）。
        // 修复前这里无条件做差集，于是 declared 为空时 metadataNotDeclared 恒等于全部元数据 →
        // consistent 恒 false + 每次启动四条 warn，运维会以为链路坏了，反而逼着去改代码。
        //
        // 物理上到底有没有这些表不靠这里判断：SchemaDrift 会查 information_schema 出
        // inDbNotInOntology / inOntologyNotInDb，比静态清单准。
        Set<String> metadataNotDeclared = new TreeSet<>();
        if (!undeclared) {
            metadataNotDeclared.addAll(metadata);
            metadataNotDeclared.removeAll(declared);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", executor.engineName());
        // metadata-only = 引擎未声明，可读集合完全由元数据决定；declared = 引擎有清单，取交集
        out.put("declarationMode", undeclared ? "metadata-only" : "declared");
        out.put("declaredTables", new ArrayList<>(declared));
        out.put("metadataTables", new ArrayList<>(metadata));
        out.put("effectiveTables", new ArrayList<>(effective));
        out.put("declaredNotInMetadata", new ArrayList<>(declaredNotInMetadata));
        out.put("metadataNotDeclared", new ArrayList<>(metadataNotDeclared));
        // consistent 必须双向：任一方向有差集都算失配（QA-07 P2-1）。
        // 此前只判 declaredNotInMetadata，于是「切到 MySQL 但元数据还指着 4 张 StarRocks 表」
        // 这个真实切换场景会报 consistent=true —— 运维按它告警就会漏掉，
        // 随后用户问一句才在 DB 层收到「SQL 执行失败」。
        out.put("consistent", declaredNotInMetadata.isEmpty() && metadataNotDeclared.isEmpty());
        return out;
    }

    private static Set<String> sorted(Set<String> in) {
        return in == null ? new TreeSet<>() : new TreeSet<>(in);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void selfCheck() {
        Map<String, Object> r = report();
        log.info("引擎白名单自检：engine={} mode={} 声明={} 元数据={} 交集={}",
                r.get("engine"), r.get("declarationMode"), r.get("declaredTables"),
                r.get("metadataTables"), r.get("effectiveTables"));
        if ("metadata-only".equals(r.get("declarationMode"))) {
            // 这是当前 StarRocks 侧的常态：引擎不写表清单，加表只动 semantic_model。
            // 打一条 info 让运维知道「没声明不是漏配」，不必去翻代码。
            log.info("引擎未声明表清单，可读集合完全由元数据决定（加表无需改代码；物理一致性看 /api/health/drift）");
        }
        List<?> missing = (List<?>) r.get("declaredNotInMetadata");
        if (missing != null && !missing.isEmpty()) {
            log.warn("引擎声明的表在元数据里不存在（这些表问题会走不过存在性闸门）：{}", missing);
        }
        List<?> extra = (List<?>) r.get("metadataNotDeclared");
        if (extra != null && !extra.isEmpty()) {
            log.warn("元数据白名单里有引擎未声明的表（当前引擎可能是切换后的数据源，确认是否有意为之）：{}", extra);
        }
    }
}
