package com.zlf.datapilot.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link SqlCommand} 的入参契约（QA-07 P2-4 回归点）。
 *
 * <p>只测外部行为：构造出来的 {@code params()} 是否可直接 {@code toArray()}。
 */
class SqlCommandTest {

    @Test
    @DisplayName("params 传 null 归一成空列表，不把 NPE 留到执行器里")
    void nullParamsBecomesEmptyList() {
        SqlCommand cmd = SqlCommand.of("SELECT COUNT(*) AS cnt FROM orders", null);

        assertThat(cmd.params()).isNotNull().isEmpty();
        assertThatCode(() -> cmd.params().toArray()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("正常 params 原样保留（顺序与元素都不动，参数绑定靠顺序）")
    void paramsPreserved() {
        List<Object> in = new ArrayList<>(List.of("2026-10-19", "2026-10-12"));

        SqlCommand cmd = new SqlCommand("SELECT * FROM ads_trade_overview_di WHERE dt > ? AND dt <= ?", in);

        assertThat(cmd.params()).containsExactly("2026-10-19", "2026-10-12");
    }

    @Test
    @DisplayName("直接用构造器传 null 也走同一归一（record 紧凑构造器，不是只在工厂方法里兜）")
    void constructorAlsoNormalizes() {
        assertThat(new SqlCommand("SELECT 1", null).params()).isEmpty();
    }
}
