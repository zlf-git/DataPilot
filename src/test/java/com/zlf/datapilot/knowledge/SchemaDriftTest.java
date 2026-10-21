package com.zlf.datapilot.knowledge;

import com.zlf.datapilot.sql.SqlExecutor;
import com.zlf.datapilot.sql.TableMeta;
import com.zlf.datapilot.support.TestOntology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语义层 ↔ 物理表巡检（Ticket 16，消费 Ticket 07 遗留的 {@code listTables()}）。
 *
 * <p>断言的是四类差异的外部表现（报告里的字段），不 assert 内部集合构造过程。
 * 巡检本身**不拦截**任何查询，所以用例也不断言 REJECT —— 它只回答「哪张表/哪一列对不上」。
 */
class SchemaDriftTest {

    @Test
    @DisplayName("两边一致：consistent=true 且四类差异全空")
    void consistentWhenNothingDrifts() {
        SchemaDrift drift = new SchemaDrift(executor(sameAsOntology()), TestOntology.cache());

        Map<String, Object> r = drift.report();

        assertThat(r.get("consistent")).isEqualTo(true);
        assertThat(asList(r, "inOntologyNotInDb")).isEmpty();
        assertThat((List<?>) r.get("inDbNotInOntology")).isEmpty();
        assertThat((Map<?, ?>) r.get("columnDrift")).isEmpty();
    }

    @Test
    @DisplayName("元数据配了、库里没有的表 → inOntologyNotInDb（表改名/删表的第一现场）")
    void reportsTableOnlyInOntology() {
        List<TableMeta> physical = new ArrayList<>(sameAsOntology());
        physical.removeIf(t -> t.getTableName().equals(TestOntology.PRODUCT));

        Map<String, Object> r = new SchemaDrift(executor(physical), TestOntology.cache()).report();

        assertThat(r.get("consistent")).isEqualTo(false);
        assertThat(asList(r, "inOntologyNotInDb")).containsExactly(TestOntology.PRODUCT);
        assertThat(asList(r, "inDbNotInOntology")).isEmpty();
    }

    @Test
    @DisplayName("库里有、元数据没配的表 → inDbNotInOntology（新表没进白名单，问不到但也没人知道）")
    void reportsTableOnlyInDb() {
        List<TableMeta> physical = new ArrayList<>(sameAsOntology());
        physical.add(new TableMeta("ads_new_gift_di", "赠品明细（还没接进语义层）"));

        Map<String, Object> r = new SchemaDrift(executor(physical), TestOntology.cache()).report();

        assertThat(r.get("consistent")).isEqualTo(false);
        assertThat(asList(r, "inDbNotInOntology")).containsExactly("ads_new_gift_di");
    }

    @Test
    @DisplayName("列漂移：库缺列与库多列分别报，不混在一起")
    void reportsColumnDrift() {
        List<TableMeta> physical = new ArrayList<>();
        for (TableMeta t : sameAsOntology()) {
            if (t.getTableName().equals(TestOntology.OVERVIEW)) {
                List<String> cols = new ArrayList<>(t.getColumns());
                cols.remove("paid_user_cnt");          // 库里删了这一列
                cols.add("refund_gmv");                // 库里新增了一列，元数据还没配
                TableMeta drifted = new TableMeta(t.getTableName(), t.getTableComment());
                drifted.getColumns().addAll(cols);
                physical.add(drifted);
            } else {
                physical.add(t);
            }
        }

        Map<String, Object> r = new SchemaDrift(executor(physical), TestOntology.cache()).report();

        assertThat(r.get("consistent")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, List<String>>> columnDrift =
                (Map<String, Map<String, List<String>>>) r.get("columnDrift");
        assertThat(columnDrift).containsKey(TestOntology.OVERVIEW);
        assertThat(columnDrift.get(TestOntology.OVERVIEW).get("missingInDb")).containsExactly("paid_user_cnt");
        assertThat(columnDrift.get(TestOntology.OVERVIEW).get("extraInDb")).containsExactly("refund_gmv");
    }

    @Test
    @DisplayName("库里读不到表时 listTables 抛异常：report 不吞异常，由调用方决定（启动自检已 try/catch）")
    void propagatesExecutorFailure() {
        SqlExecutor broken = mock(SqlExecutor.class);
        when(broken.listTables()).thenThrow(new IllegalStateException("connect refused"));

        SchemaDrift drift = new SchemaDrift(broken, TestOntology.cache());

        // report() 让异常冒出去（便于 /api/health/drift 直接暴露真实原因），
        // 启动自检 onReady() 才兜住 —— 库连不上不该让应用起不来
        org.assertj.core.api.Assertions.assertThatThrownBy(drift::report)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connect refused");
        drift.onReady(); // 不抛即通过
    }

    // ---------------- 夹具 ----------------

    /** 物理表镜像：列与 TestOntology 的元数据完全一致 */
    private static List<TableMeta> sameAsOntology() {
        List<TableMeta> out = new ArrayList<>();
        TestOntology.tableFields().forEach((table, cols) -> {
            TableMeta t = new TableMeta(table, table);
            t.getColumns().addAll(cols);
            out.add(t);
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Map<String, Object> report, String key) {
        return (List<String>) report.get(key);
    }

    private static SqlExecutor executor(List<TableMeta> tables) {
        SqlExecutor ex = mock(SqlExecutor.class);
        when(ex.listTables()).thenReturn(tables);
        when(ex.engineName()).thenReturn("starrocks");
        return ex;
    }
}
