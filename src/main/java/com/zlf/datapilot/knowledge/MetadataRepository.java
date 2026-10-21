package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.knowledge.model.DimensionDef;
import com.zlf.datapilot.knowledge.model.FewShotExample;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import com.zlf.datapilot.knowledge.model.GateConfig;
import com.zlf.datapilot.knowledge.model.MetricDef;
import com.zlf.datapilot.knowledge.model.SemanticModel;
import com.zlf.datapilot.knowledge.model.TermDict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
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
        m.setDenominatorTable(rs.getString("denominator_table"));
        m.setDenominatorSql(rs.getString("denominator_sql"));
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

    private static final RowMapper<DimensionDef> DIMENSION_MAPPER = (rs, i) -> {
        DimensionDef d = new DimensionDef();
        d.setId(rs.getLong("id"));
        d.setDimName(rs.getString("dim_name"));
        d.setFieldName(rs.getString("field_name"));
        d.setTableName(rs.getString("table_name"));
        d.setDimType(rs.getString("dim_type"));
        d.setEnumValues(rs.getString("enum_values"));
        return d;
    };

    private static final String METRIC_COLUMNS =
            "id, metric_id, metric_name, metric_alias, department, metric_sql, metric_type, table_name, dimensions, owner, status, "
                    + "denominator_table, denominator_sql";

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

    public MetricDef findByMetricId(String metricId) {
        List<MetricDef> list = jdbc.query(
                "SELECT " + METRIC_COLUMNS + " FROM metric_def WHERE metric_id = ?",
                METRIC_MAPPER, metricId);
        return list.isEmpty() ? null : list.get(0);
    }

    public MetricDef findByMetricName(String metricName) {
        List<MetricDef> list = jdbc.query(
                "SELECT " + METRIC_COLUMNS + " FROM metric_def WHERE metric_name = ?",
                METRIC_MAPPER, metricName);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 维度字典全量（DETAIL 模式 field→table 反查用） */
    public List<DimensionDef> listDimensions() {
        return jdbc.query(
                "SELECT id, dim_name, field_name, table_name, dim_type, enum_values FROM dimension_def",
                DIMENSION_MAPPER);
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

    public GateConfig findGateConfig(String gateName, String paramKey) {
        List<GateConfig> list = jdbc.query(
                "SELECT id, gate_name, param_key, param_value, updated_by FROM gate_config WHERE gate_name = ? AND param_key = ?",
                GATE_MAPPER, gateName, paramKey);
        return list.isEmpty() ? null : list.get(0);
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
        MetricDef existing = findByMetricId(m.getMetricId());
        if (existing != null) {
            // 已存在（按 metric_id）→ 只 UPDATE 这一行，绝不用 ON DUPLICATE KEY 兜两个唯一键
            jdbc.update(
                    "UPDATE metric_def SET metric_name = ?, metric_alias = ?, department = ?, metric_sql = ?, " +
                    "metric_type = ?, table_name = ?, dimensions = ?, owner = ?, status = ?, " +
                    "denominator_table = ?, denominator_sql = ? WHERE metric_id = ?",
                    m.getMetricName(), m.getMetricAlias(), m.getDepartment(), m.getMetricSql(),
                    m.getMetricType(), m.getTableName(), m.getDimensions(), m.getOwner(), m.getStatus(),
                    m.getDenominatorTable(), m.getDenominatorSql(), m.getMetricId());
            m.setId(existing.getId());
        } else {
            // 新增 → INSERT 并回填自增主键
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO metric_def (metric_id, metric_name, metric_alias, department, metric_sql, " +
                        "metric_type, table_name, dimensions, owner, status, denominator_table, denominator_sql) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, m.getMetricId());
                ps.setString(2, m.getMetricName());
                ps.setString(3, m.getMetricAlias());
                ps.setString(4, m.getDepartment());
                ps.setString(5, m.getMetricSql());
                ps.setString(6, m.getMetricType());
                ps.setString(7, m.getTableName());
                ps.setString(8, m.getDimensions());
                ps.setString(9, m.getOwner());
                ps.setString(10, m.getStatus());
                ps.setString(11, m.getDenominatorTable());
                ps.setString(12, m.getDenominatorSql());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                m.setId(key.longValue());
            }
        }
    }

    public void saveGateConfig(GateConfig g) {
        GateConfig existing = findGateConfig(g.getGateName(), g.getParamKey());
        if (existing != null) {
            jdbc.update(
                    "UPDATE gate_config SET param_value = ?, updated_by = ? WHERE gate_name = ? AND param_key = ?",
                    g.getParamValue(), g.getUpdatedBy(), g.getGateName(), g.getParamKey());
            g.setId(existing.getId());
        } else {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO gate_config (gate_name, param_key, param_value, updated_by) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, g.getGateName());
                ps.setString(2, g.getParamKey());
                ps.setString(3, g.getParamValue());
                ps.setString(4, g.getUpdatedBy());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                g.setId(key.longValue());
            }
        }
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
