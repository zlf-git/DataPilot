package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.knowledge.model.FewShotExample;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.GateConfig;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import com.zlf.datapilot.knowledge.model.TermDict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 元数据访问层：元数据表的 CRUD（对齐 schema.sql v2）。
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
        m.setFieldRole(rs.getString("field_role"));
        m.setEnumValues(rs.getString("enum_values"));
        return m;
    };

    private static final RowMapper<MetricDef> METRIC_MAPPER = (rs, i) -> {
        MetricDef m = new MetricDef();
        m.setId(rs.getLong("id"));
        m.setMetricId(rs.getString("metric_id"));
        m.setMetricName(rs.getString("metric_name"));
        m.setMetricAlias(rs.getString("metric_alias"));
        m.setDepartment(rs.getString("department"));
        m.setMetricSql(rs.getString("metric_sql"));
        m.setMetricType(rs.getString("metric_type"));
        m.setTableName(rs.getString("table_name"));
        m.setDimensions(rs.getString("dimensions"));
        m.setOwner(rs.getString("owner"));
        m.setStatus(rs.getString("status"));
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

    private static final RowMapper<GateConfig> GATE_MAPPER = (rs, i) -> {
        GateConfig g = new GateConfig();
        g.setId(rs.getLong("id"));
        g.setGateName(rs.getString("gate_name"));
        g.setParamKey(rs.getString("param_key"));
        g.setParamValue(rs.getString("param_value"));
        g.setUpdatedBy(rs.getString("updated_by"));
        return g;
    };

    private static final String METRIC_COLUMNS =
            "id, metric_id, metric_name, metric_alias, department, metric_sql, metric_type, table_name, dimensions, owner, status";

    private static final String FIELD_COLUMNS =
            "id, table_name, field_name, field_type, field_comment, field_role, enum_values";

    // ============ 查询 ============

    public List<SemanticModel> listTables() {
        return jdbc.query(
                "SELECT id, table_name, table_comment, biz_domain FROM semantic_model",
                TABLE_MAPPER);
    }

    public List<FieldMetadata> listFields(String tableName) {
        return jdbc.query(
                "SELECT " + FIELD_COLUMNS + " FROM field_metadata WHERE table_name = ?",
                FIELD_MAPPER, tableName);
    }

    public List<FieldMetadata> listAllFields() {
        return jdbc.query(
                "SELECT " + FIELD_COLUMNS + " FROM field_metadata",
                FIELD_MAPPER);
    }

    public List<MetricDef> listMetrics() {
        return jdbc.query(
                "SELECT " + METRIC_COLUMNS + " FROM metric_def",
                METRIC_MAPPER);
    }

    public List<TermDict> listTerms() {
        return jdbc.query("SELECT id, term, field_ref FROM term_dict", TERM_MAPPER);
    }

    public List<FewShotExample> listFewShots(String bizDomain) {
        if (bizDomain == null || bizDomain.isBlank()) {
            return jdbc.query(
                    "SELECT id, question, `sql`, biz_domain FROM few_shot_example",
                    FEWSHOT_MAPPER);
        }
        return jdbc.query(
                "SELECT id, question, `sql`, biz_domain FROM few_shot_example WHERE biz_domain = ?",
                FEWSHOT_MAPPER, bizDomain);
    }

    public List<GateConfig> listGateConfigs() {
        return jdbc.query(
                "SELECT id, gate_name, param_key, param_value, updated_by FROM gate_config",
                GATE_MAPPER);
    }

    public int countMetrics() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM metric_def", Integer.class);
    }

    public int countFields() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM field_metadata", Integer.class);
    }

    public int countGateConfigs() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gate_config", Integer.class);
    }

    // ============ 维护（元数据 CRUD） ============

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
                "INSERT INTO metric_def (metric_id, metric_name, metric_alias, department, metric_sql, metric_type, table_name, dimensions, owner, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE metric_name = VALUES(metric_name), metric_alias = VALUES(metric_alias), department = VALUES(department), " +
                "metric_sql = VALUES(metric_sql), metric_type = VALUES(metric_type), table_name = VALUES(table_name), " +
                "dimensions = VALUES(dimensions), owner = VALUES(owner), status = VALUES(status)",
                m.getMetricId(), m.getMetricName(), m.getMetricAlias(), m.getDepartment(), m.getMetricSql(),
                m.getMetricType(), m.getTableName(), m.getDimensions(), m.getOwner(), m.getStatus());
    }

    public void saveGateConfig(GateConfig g) {
        jdbc.update(
                "INSERT INTO gate_config (gate_name, param_key, param_value, updated_by) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE param_value = VALUES(param_value), updated_by = VALUES(updated_by)",
                g.getGateName(), g.getParamKey(), g.getParamValue(), g.getUpdatedBy());
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
