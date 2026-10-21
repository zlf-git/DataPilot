package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.sql.SqlExecutor;
import com.zlf.datapilot.sql.TableMeta;
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
 * 语义模型 ↔ 物理表的一致性巡检（Ticket 16，消费 Ticket 07 遗留的 {@link SqlExecutor#listTables()}）。
 *
 * <p>{@code EngineWhitelist} 比的是「引擎声明 ↔ 元数据白名单」两份清单；本类比的是
 * 「<b>元数据白名单 ↔ 数据库里真实存在的表/列</b>」—— 前者发现「引擎换了但元数据没换」，
 * 后者发现「元数据写了但库里没有」（改名、删列、换库、seed 漏跑）。
 *
 * <p>为什么值得单独巡检：这类漂移在问数链路上的表现是「指标存在、闸门全 PASS、执行期 SQL 报列不存在」，
 * 用户看到的是「查询失败」，排查却要从闸门一路翻到 DDL。<b>本类只诊断不拦截</b>：
 * 拒绝判定归闸门①②，这里只把差异摊开（启动打一行日志 + {@code GET /api/health/drift} 可查）。
 *
 * <p>这也是 {@code listTables()} 在 Ticket 07 之后第一次有真实调用方 —— 此前它是「没人用但舍不得删」的方法。
 */
@Component
public class SchemaDrift {

    private static final Logger log = LoggerFactory.getLogger(SchemaDrift.class);

    private final SqlExecutor executor;
    private final OntologyCache cache;

    public SchemaDrift(SqlExecutor executor, OntologyCache cache) {
        this.executor = executor;
        this.cache = cache;
    }

    /** 巡检报告：四类差异 + 是否一致的总判定（供启动日志与健康接口共用） */
    public Map<String, Object> report() {
        Map<String, List<String>> physical = physicalColumns();
        Set<String> semanticTables = new TreeSet<>(cache.tables());
        Set<String> physicalTables = new TreeSet<>(physical.keySet());

        Set<String> inOntologyNotInDb = new TreeSet<>(semanticTables);
        inOntologyNotInDb.removeAll(physicalTables);
        Set<String> inDbNotInOntology = new TreeSet<>(physicalTables);
        inDbNotInOntology.removeAll(semanticTables);

        // 只比两边都有的表：单边存在的表已经在上面两条里报了，再列一遍列差异是噪声
        Map<String, Object> columnDrift = new LinkedHashMap<>();
        for (String table : physicalTables) {
            if (!semanticTables.contains(table)) {
                continue;
            }
            Set<String> dbCols = new TreeSet<>(physical.get(table));
            Set<String> ontoCols = new TreeSet<>(ontologyColumns(table));
            Set<String> missingInDb = new TreeSet<>(ontoCols);
            missingInDb.removeAll(dbCols);
            Set<String> extraInDb = new TreeSet<>(dbCols);
            extraInDb.removeAll(ontoCols);
            if (!missingInDb.isEmpty() || !extraInDb.isEmpty()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("missingInDb", new ArrayList<>(missingInDb));
                item.put("extraInDb", new ArrayList<>(extraInDb));
                columnDrift.put(table, item);
            }
        }

        boolean consistent = inOntologyNotInDb.isEmpty() && inDbNotInOntology.isEmpty() && columnDrift.isEmpty();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", executor.engineName());
        out.put("consistent", consistent);
        out.put("ontologyTables", new ArrayList<>(semanticTables));
        out.put("physicalTables", new ArrayList<>(physicalTables));
        out.put("inOntologyNotInDb", new ArrayList<>(inOntologyNotInDb));
        out.put("inDbNotInOntology", new ArrayList<>(inDbNotInOntology));
        out.put("columnDrift", columnDrift);
        return out;
    }

    /** 启动自检：只打日志，不影响启动（库暂时连不上不该让应用起不来） */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            Map<String, Object> r = report();
            if (Boolean.TRUE.equals(r.get("consistent"))) {
                log.info("语义层 ↔ 物理表一致性巡检通过（{} 张表，引擎 {}）",
                        ((List<?>) r.get("ontologyTables")).size(), r.get("engine"));
            } else {
                log.warn("语义层 ↔ 物理表存在漂移：元数据有库里没有 {}，库里有元数据没配 {}，列差异 {} 张表（详见 /api/health/drift）",
                        r.get("inOntologyNotInDb"), r.get("inDbNotInOntology"),
                        ((Map<?, ?>) r.get("columnDrift")).size());
            }
        } catch (RuntimeException e) {
            log.warn("语义层 ↔ 物理表巡检跳过：{}", e.toString());
        }
    }

    /** 表名 → 列集合（引擎侧镜像；listTables 返回的列顺序即 DDL 顺序，这里不用保序） */
    private Map<String, List<String>> physicalColumns() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (TableMeta t : executor.listTables()) {
            out.put(t.getTableName(), new ArrayList<>(t.getColumns()));
        }
        return out;
    }

    /** 元数据里该表配置的字段（从 table.field 复合键里摘出来） */
    private List<String> ontologyColumns(String table) {
        String prefix = table + ".";
        List<String> cols = new ArrayList<>();
        for (String tf : cache.tableFields()) {
            if (tf.startsWith(prefix)) {
                cols.add(tf.substring(prefix.length()));
            }
        }
        return cols;
    }
}
