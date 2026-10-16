package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.knowledge.model.FewShotExample;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import com.zlf.datapilot.knowledge.model.TermDict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 元数据访问层：4 张元数据表（+few-shot）的 CRUD。
 * 用 JdbcTemplate 实现，不引 MyBatis/JPA（最小依赖）。
 */
@Repository
public class MetadataRepository {

    private final JdbcTemplate jdbc;

    public MetadataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SemanticModel> TABLE_MAPPER = (rs, i) -> {
        SemanticModel m = new SemanticModel();
        m.setId(rs.getLong("id"));
        m.setTableName(rs.getString("table_name"));
        m.setTableComment(rs.getString("table_comment"));
        m.setBizDomain(rs.getString("biz_domain"));
        return m;
    };

    private static final RowMapper<FieldMetadata> FIELD_MAPPER = (rs, i) -> {
        FieldMetadata m = new FieldMetadata();
        m.setId(rs.getLong("id"));
        m.setTableName(rs.getString("table_name"));
        m.setFieldName(rs.getString("field_name"));
        m.setFieldType(rs.getString("field_type"));
        m.setFieldComment(rs.getString("field_comment"));
        m.setEnumValues(rs.getString("enum_values"));
        return m;
    };

    private static final RowMapper<MetricDef> METRIC_MAPPER = (rs, i) -> {
        MetricDef m = new MetricDef();
        m.setId(rs.getLong("id"));
        m.setMetricName(rs.getString("metric_name"));
        m.setEnName(rs.getString("en_name"));
        m.setBizDomain(rs.getString("biz_domain"));
        m.setDefinition(rs.getString("definition"));
        return m;
    };

    private static final RowMapper<TermDict> TERM_MAPPER = (rs, i) -> {
        TermDict m = new TermDict();
        m.setId(rs.getLong("id"));
        m.setTerm(rs.getString("term"));
        m.setFieldRef(rs.getString("field_ref"));
        return m;
    };

    private static final RowMapper<FewShotExample> FEWSHOT_MAPPER = (rs, i) -> {
        FewShotExample m = new FewShotExample();
        m.setId(rs.getLong("id"));
        m.setQuestion(rs.getString("question"));
        m.setSql(rs.getString("sql"));
        m.setBizDomain(rs.getString("biz_domain"));
        return m;
    };

    // ============ 查询 ============

    public List<SemanticModel> listTables() {
        return jdbc.query(
                "SELECT id, table_name, table_comment, biz_domain FROM semantic_model",
                TABLE_MAPPER);
    }

    public List<FieldMetadata> listFields(String tableName) {
        return jdbc.query(
                "SELECT id, table_name, field_name, field_type, field_comment, enum_values FROM field_metadata WHERE table_name = ?",
                FIELD_MAPPER, tableName);
    }

    public List<FieldMetadata> listAllFields() {
        return jdbc.query(
                "SELECT id, table_name, field_name, field_type, field_comment, enum_values FROM field_metadata",
                FIELD_MAPPER);
    }

    public List<MetricDef> listMetrics(String bizDomain) {
        if (bizDomain == null || bizDomain.isBlank()) {
            return jdbc.query(
                    "SELECT id, metric_name, en_name, biz_domain, definition FROM metric_def",
                    METRIC_MAPPER);
        }
        return jdbc.query(
                "SELECT id, metric_name, en_name, biz_domain, definition FROM metric_def WHERE biz_domain = ?",
                METRIC_MAPPER, bizDomain);
    }

    public List<TermDict> listTerms() {
        return jdbc.query("SELECT id, term, field_ref FROM term_dict", TERM_MAPPER);
    }

    public List<FewShotExample> listFewShots(String bizDomain) {
        if (bizDomain == null || bizDomain.isBlank()) {
            return jdbc.query(
                    "SELECT id, question, sql, biz_domain FROM few_shot_example",
                    FEWSHOT_MAPPER);
        }
        return jdbc.query(
                "SELECT id, question, sql, biz_domain FROM few_shot_example WHERE biz_domain = ?",
                FEWSHOT_MAPPER, bizDomain);
    }

    // ============ 维护（元数据 CRUD，Day5 的 Controller 使用） ============

    public void saveTable(SemanticModel m) {
        jdbc.update(
                "INSERT INTO semantic_model (table_name, table_comment, biz_domain) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE table_comment = VALUES(table_comment), biz_domain = VALUES(biz_domain)",
                m.getTableName(), m.getTableComment(), m.getBizDomain());
    }

    public void saveField(FieldMetadata f) {
        jdbc.update(
                "INSERT INTO field_metadata (table_name, field_name, field_type, field_comment, enum_values) VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE field_type = VALUES(field_type), field_comment = VALUES(field_comment), enum_values = VALUES(enum_values)",
                f.getTableName(), f.getFieldName(), f.getFieldType(), f.getFieldComment(), f.getEnumValues());
    }

    public void saveMetric(MetricDef m) {
        jdbc.update(
                "INSERT INTO metric_def (metric_name, en_name, biz_domain, definition) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE en_name = VALUES(en_name), biz_domain = VALUES(biz_domain), definition = VALUES(definition)",
                m.getMetricName(), m.getEnName(), m.getBizDomain(), m.getDefinition());
    }

    public void saveTerm(TermDict t) {
        jdbc.update(
                "INSERT INTO term_dict (term, field_ref) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE field_ref = VALUES(field_ref)",
                t.getTerm(), t.getFieldRef());
    }

    public void saveFewShot(FewShotExample e) {
        jdbc.update(
                "INSERT INTO few_shot_example (question, sql, biz_domain) VALUES (?, ?, ?)",
                e.getQuestion(), e.getSql(), e.getBizDomain());
    }
}
