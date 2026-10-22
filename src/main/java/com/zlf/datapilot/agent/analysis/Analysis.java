package com.zlf.datapilot.agent.analysis;

import java.util.List;

/**
 * 一次分析的结果（Ticket 21）：<b>结论</b>与<b>缺口</b>必须分开承载。
 *
 * <p>为什么不合成一个 {@code List<String>}：两者对用户的意义相反 ——
 * {@code findings} 是「数据支持的结论」，{@code gaps} 是「问题要求了但这次答不出来的部分」。
 * 混在一起前端没法分色，人也没法一眼看出「这份答案是完整的还是残缺的」。
 *
 * <p>{@code gaps} 非空<b>不代表失败</b>：这次 SQL 跑通了、数字是真的，
 * 只是问题的某几个分句在当前取数下无解。把它显式列出来，比让人误以为「全部回答完了」诚实得多。
 *
 * <h3>为什么还要 summary（Ticket 22）</h3>
 * 只有 {@code findings} 时，页面顶部仍然在念「共 2 行（category_name → sale_cnt_product）：
 * 办公家具=34、电脑外设=58」—— 数字念了一遍，一句判断都没有。用户看到的观感是「不够智能」。
 * {@code summary} 是<b>唯一一句</b>直接回答问题的判断（如「销量与销售额结构基本一致，
 * 差 2.3 个百分点」），用来替换那句话。<b>与 findings 不重复</b>：findings 是支撑它的量化事实。
 *
 * @param summary  一句话结论，直接回答问题的主句；空串表示这次提炼不出判断
 * @param findings 能算出来的量化结论，每条一句话，已按重要性排序
 * @param gaps     答不了的部分 + 缺什么，空表示这次把问题覆盖全了
 */
public record Analysis(String summary, List<String> findings, List<String> gaps) {

    /** 空分析：没有结果行、或行里没有任何数值列时用它，前端据此不渲染分析区 */
    public static final Analysis EMPTY = new Analysis("", List.of(), List.of());

    public Analysis {
        summary = summary == null ? "" : summary.trim();
        findings = findings == null ? List.of() : List.copyOf(findings);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }

    /** 兼容旧的两参构造（测试与调用方大量使用） */
    public Analysis(List<String> findings, List<String> gaps) {
        this("", findings, gaps);
    }

    /** 一条结论都没有 = 这次分析没东西可说（前端不渲染） */
    public boolean isEmpty() {
        return summary.isEmpty() && findings.isEmpty() && gaps.isEmpty();
    }
}
