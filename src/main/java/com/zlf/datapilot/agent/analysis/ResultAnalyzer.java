package com.zlf.datapilot.agent.analysis;

import com.zlf.datapilot.ir.MetricRef;
import com.zlf.datapilot.ir.QueryIr;
import com.zlf.datapilot.knowledge.model.MetricDef;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 结果分析器（Ticket 21）：把「一串数字」变成「几句结论」，并诚实标出答不了的部分。
 *
 * <h3>为什么是模板而不是再调一次 LLM</h3>
 * 这是 {@code ExplainStep} 既有取舍的延续：同一份 rows 交给模型自由发挥，
 * 它有机会说出「金额占比与销量占比基本匹配」这种数据里没有的判断（幻觉里最贵的一种，
 * 因为它读起来最像结论）。模板只能说数据里有的东西 —— 但「数据里有的」其实比想象中多：
 * 占比、集中度、相对均值的偏离、极值差、离散度、趋势拐点，全部只需 rows 本身就能算准。
 *
 * <h3>两类输出必须分开</h3>
 * {@link Analysis#findings()} 是算出来的结论；{@link Analysis#gaps()} 是<b>问题要求了、
 * 但这次取数覆盖不到</b>的部分。后者是这张票最关键的产出 —— 比如问「销量占比与金额占比是否匹配」，
 * 只取了销量，那就必须明说「金额没取，匹配与否无法判断」，而不是默默给一张销量柱状图让人以为答完了。
 *
 * <h3>边界</h3>
 * 本类<b>只做通用数值分析</b>，不认识任何具体业务口径（哪张表全量、哪个字段是模拟值）。
 * 业务口径属于 Ontology 元数据的职责，塞进通用分析器会让它随业务漂移。
 */
@Component
public final class ResultAnalyzer {

    /** 结论条数上限：再多就成了「又一份明细表」，与「说结论」的初衷相反 */
    private static final int MAX_FINDINGS = 8;

    /** 占比明细最多列几项，其余归入「其他」 */
    private static final int MAX_SHARE_ITEMS = 3;

    /** 相对均值偏离超过这个比例才算「偏高/偏低」，避免 2% 的噪音被说成结论 */
    private static final double BIAS_THRESHOLD = 0.10;

    /** 头部占比达到这个值称「集中」 */
    private static final double CONCENTRATED = 0.60;

    /** 头部占比低于这个值且项数够多，称「分散」 */
    private static final double DISPERSED = 0.40;

    /** 离散系数（标准差/均值）超过这个值称波动明显 */
    private static final double HIGH_CV = 0.50;

    /** 问题里出现这些词，说明要的是「金额类」量值 */
    private static final String[] MONEY_WORDS = {
            "金额", "销售额", "营收", "gmv", "GMV", "客单价", "件单价", "单价", "价格", "收入"
    };

    /** 问题里出现这些词，说明要的是「数量类」量值 */
    private static final String[] COUNT_WORDS = {
            "销量", "订单数", "单量", "件数", "数量", "笔数", "人数", "用户数", "人次", "券数"
    };

    /** 问题里出现这些词，说明要做两量对比 / 对账 */
    private static final String[] COMPARE_WORDS = {"匹配", "一致", "对账", "吻合", "相符", "偏差"};

    /** 问题里出现这些词，说明要看时间上的变化 */
    private static final String[] TREND_WORDS = {"趋势", "变化", "增长", "下降", "涨", "跌", "环比", "同比", "波动"};

    /**
     * 主入口。
     *
     * @param rows       查询结果（已按 IR 的 orderBy/limit 排好）
     * @param ir         本次 IR，用来判断「问题要求的量值取到了没有」；可为 null（预览等场景）
     * @param metricDefs 召回到的指标定义，用来把 metricId 显示成中文名；可为 null
     * @param question   用户原始问题，用来做缺口识别；为空则只做数值分析
     */
    public Analysis analyze(List<Map<String, Object>> rows, QueryIr ir,
                            List<MetricDef> metricDefs, String question) {
        if (rows == null || rows.isEmpty()) {
            return Analysis.EMPTY;
        }
        List<String> findings = new ArrayList<>();
        SummarySlot verdicts = new SummarySlot();
        List<Column> numeric = numericColumns(rows, metricDefs);
        String labelKey = firstLabelKey(rows, numeric);

        if (numeric.isEmpty()) {
            // 纯文本结果（如明细 DISTINCT）：没有可算的量，不硬凑结论
            return Analysis.EMPTY;
        }
        if (labelKey == null) {
            // 没有维度列（单值结果、多指标各占一列）：占比、集中度、均值偏离全都无从谈起 ——
            // 硬算只能得到「n1合计 7；1 占 14.3%」这种没人读得懂的句子（Ticket 22 实测踩到）
            return new Analysis("", List.of(), detectGaps(numeric, ir, metricDefs, question));
        }
        boolean temporal = isTemporal(rows, labelKey);
        // 两列份额对照放最前：「A 占比与 B 占比是否匹配」通常是问题的主句，
        // 分别列出两组占比让人自己心算，等于没回答问题
        boolean sharesCovered = addShareMismatchFindings(findings, rows, labelKey, numeric, verdicts);
        // 派生结论（金额÷数量 这类两列之间的比值）紧随其后：「件单价偏高还是偏低」往往是第二问
        addDerivedFindings(findings, rows, labelKey, numeric, metricDefs, question, verdicts);
        // 多列时不逐列展开分布细节（Ticket 22）：
        // 份额对照已经逐项给了两列的占比，再对每列重复「合计 + 集中在某项（占 X）」，
        // 就是同一句话说两遍 —— 8 条结论里 4 条是这种复述，观感是「啰嗦」而不是「智能」。
        boolean lean = sharesCovered || numeric.size() > 1;
        for (Column col : numeric) {
            if (temporal) {
                addTemporalFindings(findings, rows, labelKey, col, verdicts);
            } else if (!sharesCovered) {
                addDistributionFindings(findings, rows, labelKey, col, lean, verdicts);
            }
        }
        List<String> gaps = detectGaps(numeric, ir, metricDefs, question);
        return new Analysis(verdicts.text(), trim(findings), gaps);
    }

    /**
     * 一句话结论的收集槽（Ticket 22）。
     *
     * <p>为什么需要它：{@link Analysis#findings()} 是<b>支撑材料</b>，页面顶部要的是
     * <b>一句判断</b>。两者不能靠事后解析字符串从 findings 里凑（句式一改就崩），
     * 所以在算的过程中就把「定性判断」单独收集，按调用顺序决定优先级 ——
     * 先算的（份额是否匹配）比后算的（哪一项最大）更接近问题的主句。
     *
     * <p>上限 2 条：一个问句通常最多两问（「是否匹配」+「偏高还是偏低」），再多就又是复述了。
     */
    private static final class SummarySlot {
        private final List<String> parts = new ArrayList<>();

        void add(String verdict) {
            if (verdict != null && !verdict.isBlank() && parts.size() < 2 && !parts.contains(verdict)) {
                parts.add(verdict.trim());
            }
        }

        String text() {
            return String.join("；", parts);
        }
    }

    // ---------------------------------------------------------------- 分布型（按维度分组）

    /**
     * 一列数值 + 一个维度列：算占比、集中度、相对均值的偏离、极值比、离散度。
     *
     * <p>「相对均值偏离」这条专门回答「X 比大盘高还是低」类问题 ——
     * 只要 rows 里有这一列，均值就是大盘，逐项比一下就能定性，不需要额外查一次。
     *
     * @param lean 多列场景（份额对照已经讲过结构）：只出「合计 + 构成」一条，
     *             不再判定集中度与离散度 —— 那两项是对占比的二次解读，重复说一遍只会稀释结论
     */
    private void addDistributionFindings(List<String> out, List<Map<String, Object>> rows,
                                         String labelKey, Column col, boolean lean,
                                         SummarySlot verdicts) {
        List<Cell> cells = cells(rows, labelKey, col.key());
        if (cells.isEmpty()) {
            return;
        }
        String name = col.display();
        double total = cells.stream().mapToDouble(Cell::value).sum();
        boolean shareable = cells.stream().allMatch(c -> c.value() >= 0) && total > 0;

        if (shareable && cells.size() > 1) {
            List<Cell> desc = cells.stream()
                    .sorted(Comparator.comparingDouble(Cell::value).reversed()).toList();
            StringBuilder sb = new StringBuilder(name).append("合计 ").append(num(total)).append("；");
            int limit = Math.min(desc.size(), MAX_SHARE_ITEMS);
            for (int i = 0; i < limit; i++) {
                Cell c = desc.get(i);
                if (i > 0) {
                    sb.append("，");
                }
                sb.append(label(c)).append(" ").append(num(c.value()))
                        .append(" 占 ").append(pct(c.value() / total));
            }
            if (desc.size() > limit) {
                double rest = desc.subList(limit, desc.size()).stream().mapToDouble(Cell::value).sum();
                sb.append("，其余 ").append(desc.size() - limit).append(" 项合计占 ").append(pct(rest / total));
            }
            out.add(sb.append("。").toString());

            double cr1 = desc.get(0).value() / total;
            // 两项构成的分布不谈「集中/分散」：任一项必然 ≥50%，这句话永远成立，等于没说。
            // ≥3 项时才是有信息量的判断（Ticket 22）
            if (!lean && cells.size() >= 3) {
                if (cr1 >= CONCENTRATED) {
                    out.add(name + "集中在" + labelPlus(label(desc.get(0)), "一项") + "（占 " + pct(cr1) + "），结构偏集中。");
                } else if (cr1 <= DISPERSED) {
                    out.add(name + "分布较分散，最大一项只占 " + pct(cr1) + "。");
                }
            }
            // 兜底判定：单列分布题没有更具体的主句时，用「合计 + 头部项」回答
            verdicts.add(name + "合计 " + num(total) + "，"
                    + labelPlus(label(desc.get(0)), "为最大项") + "（占 " + pct(cr1) + "）");
        }

        // 均值偏离与离散度：只在单列时展开（多列时让位给份额对照与派生比值）
        if (!lean) {
            addDispersionFindings(out, name, cells);
        }
    }

    /** 相对均值的偏离 + 极值差 + 离散系数 */
    private void addDispersionFindings(List<String> out, String name, List<Cell> cells) {
        double total = cells.stream().mapToDouble(Cell::value).sum();
        double mean = total / cells.size();
        if (mean > 0 && cells.size() > 1) {
            List<Cell> biased = cells.stream()
                    .filter(c -> Math.abs(c.value() - mean) / mean >= BIAS_THRESHOLD)
                    .sorted(Comparator.comparingDouble((Cell c) -> Math.abs(c.value() - mean)).reversed())
                    .limit(MAX_SHARE_ITEMS)
                    .toList();
            if (!biased.isEmpty()) {
                StringBuilder sb = new StringBuilder("相对均值 ").append(num(mean)).append("：");
                for (int i = 0; i < biased.size(); i++) {
                    Cell c = biased.get(i);
                    double diff = (c.value() - mean) / mean;
                    if (i > 0) {
                        sb.append("，");
                    }
                    sb.append(labelPlus(label(c), diff > 0 ? "高 " : "低 ")).append(pct(Math.abs(diff)));
                }
                out.add(sb.append("。").toString());
            }
        }

        // 极值差与离散度：判断「这几个数是不是在一个量级」
        DoubleSummary summary = summarize(cells);
        if (summary.min() > 0 && summary.max() / summary.min() >= 1.5) {
            out.add(name + "最高 " + num(summary.max()) + "（" + label(summary.maxCell())
                    + "），最低 " + num(summary.min()) + "（" + label(summary.minCell())
                    + "），极值相差 " + ratio(summary.max() / summary.min()) + " 倍。");
        }
        if (summary.cv() >= HIGH_CV && cells.size() >= 3) {
            out.add(name + "离散系数 " + String.format(Locale.ROOT, "%.2f", summary.cv())
                    + "，各项之间波动明显。");
        }
    }

    // ---------------------------------------------------------------- 时间型（按日期）

    /**
     * 一列数值 + 日期列：算峰值谷值、首尾变化、最近一期环比。
     *
     * <p>不排序直接看首尾，是因为 rows 按 IR 的 orderBy 来 —— 日期列必须时间正序才这么算，
     * 所以这里显式按日期排一遍，不假设调用方给了什么顺序。
     */
    private void addTemporalFindings(List<String> out, List<Map<String, Object>> rows,
                                     String labelKey, Column col, SummarySlot verdicts) {
        List<Cell> cells = cells(rows, labelKey, col.key());
        if (cells.isEmpty()) {
            return;
        }
        List<Cell> ordered = cells.stream()
                .sorted(Comparator.comparing(c -> String.valueOf(c.label()))).toList();
        String name = col.display();
        DoubleSummary summary = summarize(ordered);

        if (ordered.size() >= 2) {
            Cell first = ordered.get(0);
            Cell last = ordered.get(ordered.size() - 1);
            if (first.value() != 0) {
                double change = (last.value() - first.value()) / Math.abs(first.value());
                out.add(name + "从 " + label(first) + " 的 " + num(first.value()) + " 变为 "
                        + label(last) + " 的 " + num(last.value()) + "，"
                        + (change >= 0 ? "上升 " : "下降 ") + pct(Math.abs(change)) + "。");
                StringBuilder headline = new StringBuilder(name)
                        .append("在 ").append(ordered.size()).append(" 个周期内")
                        .append(change >= 0 ? "上升 " : "下降 ").append(pct(Math.abs(change)));
                Cell prev = ordered.get(ordered.size() - 2);
                if (prev.value() != 0) {
                    double dod = (last.value() - prev.value()) / Math.abs(prev.value());
                    headline.append("，最近一期环比").append(dod >= 0 ? "上升 " : "下降 ").append(pct(Math.abs(dod)));
                }
                verdicts.add(headline.toString());
            }
            Cell prev = ordered.get(ordered.size() - 2);
            if (prev.value() != 0) {
                double dod = (last.value() - prev.value()) / Math.abs(prev.value());
                out.add("最近一期环比：" + label(last) + " 相对 " + label(prev) + " "
                        + (dod >= 0 ? "上升 " : "下降 ") + pct(Math.abs(dod)) + "。");
            }
        }
        out.add(name + "峰值 " + num(summary.max()) + "（" + label(summary.maxCell())
                + "），谷值 " + num(summary.min()) + "（" + label(summary.minCell())
                + "），" + ordered.size() + " 个周期均值 " + num(summary.mean()) + "。");
        if (summary.min() > 0 && summary.max() / summary.min() >= 1.5) {
            out.add("峰谷相差 " + ratio(summary.max() / summary.min())
                    + " 倍，周期之间差异明显，不宜只看平均值。");
        }
    }

    // ---------------------------------------------------------------- 两列份额对照

    /**
     * 两列份额对照：直接回答「A 占比与 B 占比是否匹配」。
     *
     * <p>光把两组占比分别列出来，人还得自己心算相减才知道「匹配不匹配」——
     * 而「是否匹配」往往正是问题的主句。这里替他把差额算成百分点（pp），并按差额排序，
     * 再给一句定性：一致 / 有差异 / 明显不一致。阈值是经验值（3pp / 10pp），
     * 说成「基本一致」而不是「完全相等」，因为抽样波动本来就不该被当成零。
     */
    private boolean addShareMismatchFindings(List<String> out, List<Map<String, Object>> rows,
                                             String labelKey, List<Column> numeric, SummarySlot verdicts) {
        if (labelKey == null || numeric.size() < 2) {
            return false;
        }
        Column first = numeric.get(0);
        Column second = numeric.get(1);
        double totalA = 0;
        double totalB = 0;
        for (Map<String, Object> row : rows) {
            totalA += asDouble(row.get(first.key()));
            totalB += asDouble(row.get(second.key()));
        }
        if (totalA <= 0 || totalB <= 0) {
            return false;
        }
        List<ShareDiff> diffs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double a = asDouble(row.get(first.key()));
            double b = asDouble(row.get(second.key()));
            if (a < 0 || b < 0) {
                return false;
            }
            String label = String.valueOf(row.get(labelKey));
            diffs.add(new ShareDiff(label, a / totalA, b / totalB, (a / totalA - b / totalB) * 100));
        }
        List<ShareDiff> desc = diffs.stream()
                .sorted(Comparator.comparingDouble((ShareDiff d) -> Math.abs(d.diffPp())).reversed())
                .toList();

        StringBuilder sb = new StringBuilder(first.display()).append("份额与").append(second.display())
                .append("份额对照：");
        int limit = Math.min(desc.size(), MAX_SHARE_ITEMS);
        for (int i = 0; i < limit; i++) {
            ShareDiff d = desc.get(i);
            if (i > 0) {
                sb.append("，");
            }
            sb.append(d.label())
                    .append(d.diffPp() >= 0 ? " +" : " ")
                    .append(BigDecimal.valueOf(d.diffPp()).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString())
                    .append("pp（").append(pct(d.shareA())).append(" vs ").append(pct(d.shareB())).append("）");
        }
        out.add(sb.append("。").toString());

        double maxAbs = desc.isEmpty() ? 0 : Math.abs(desc.get(0).diffPp());
        String verdict;
        String headline;
        if (maxAbs <= 3) {
            verdict = "差异在 3 个百分点以内，两个量值的结构基本一致。";
            headline = first.display() + "与" + second.display() + "结构基本一致，最大差 "
                    + oneDecimal(maxAbs) + " 个百分点";
        } else if (maxAbs <= 10) {
            verdict = "最大差异约 " + oneDecimal(maxAbs) + " 个百分点，存在结构性差异但不悬殊。";
            headline = first.display() + "与" + second.display() + "有结构性差异（最大差 "
                    + oneDecimal(maxAbs) + " 个百分点），但整体次序一致";
        } else {
            verdict = "最大差异达 " + oneDecimal(maxAbs) + " 个百分点，两个量值的结构明显不一致 —— "
                    + "说明高价与低价品类的贡献不同。";
            headline = first.display() + "与" + second.display() + "结构明显不一致（最大差 "
                    + oneDecimal(maxAbs) + " 个百分点），高价与低价项贡献不同";
        }
        out.add(verdict);
        verdicts.add(headline);

        // 两列合计单独给一句：份额对照只给百分比，绝对规模仍要能一眼看到（Ticket 22）。
        // 有了这句，就不必再对每列重复「X 合计 N；A 占 p%」—— 那正是复述的来源
        out.add("两列合计：" + first.display() + " " + num(totalA)
                + "，" + second.display() + " " + num(totalB) + "。");
        return true;
    }

    private static String oneDecimal(double v) {
        return BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private record ShareDiff(String label, double shareA, double shareB, double diffPp) {
    }

    private double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    // ---------------------------------------------------------------- 派生比值（金额 ÷ 数量）

    /**
     * 两列之间的派生比值：金额 ÷ 数量 = 单位数量金额（件单价／客单价）。
     *
     * <p>为什么只做这一种比：在通用数值分析里，它几乎是唯一「两列相除仍有明确业务含义」的组合。
     * 其它组合（GMV÷订单数、GMV÷支付用户数）含义随问题漂移，硬算出来的是「凑出来的比」，
     * 当成结论输出比不输出更糟 —— 那正是这一层想避免的幻觉。
     *
     * <p>回答的是「某维度比整体高还是低」：逐项比值 vs <b>整体比值（总额÷总量）</b>。
     * 注意整体比值不等于逐项比值的平均，用后者会把大额项和小额项等权，得出相反的结论。
     */
    private void addDerivedFindings(List<String> out, List<Map<String, Object>> rows, String labelKey,
                                    List<Column> numeric, List<MetricDef> metricDefs, String question,
                                    SummarySlot verdicts) {
        if (labelKey == null || numeric.size() < 2) {
            return;
        }
        Column money = null;
        Column count = null;
        for (Column c : numeric) {
            // 列名和口径 SQL 一起看：指标叫 sale_amount_product 字面含 amount，但叫 avg_order_value 的只能靠口径认
            String sig = (c.key() + " " + metricSqlOf(c.key(), metricDefs)).toLowerCase(Locale.ROOT);
            if (money == null && containsAny(sig, MONEY_SIGNALS)) {
                money = c;
            } else if (count == null && containsAny(sig, COUNT_SIGNALS)) {
                count = c;
            }
        }
        if (money == null || count == null || money.key().equals(count.key())) {
            return;
        }

        double moneyTotal = 0;
        double countTotal = 0;
        List<Cell> ratios = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object mv = row.get(money.key());
            Object cv = row.get(count.key());
            if (!(mv instanceof Number m) || !(cv instanceof Number c)) {
                continue;
            }
            moneyTotal += m.doubleValue();
            countTotal += c.doubleValue();
            if (c.doubleValue() != 0) {
                ratios.add(new Cell(String.valueOf(row.get(labelKey)), m.doubleValue() / c.doubleValue()));
            }
        }
        if (ratios.isEmpty() || countTotal == 0) {
            return;
        }
        double overall = moneyTotal / countTotal;
        String unit = question != null && question.contains("单价") ? "件单价" : "金额÷数量";
        String head = unit + "（" + money.display() + " ÷ " + count.display() + "）";

        List<Cell> desc = ratios.stream()
                .sorted(Comparator.comparingDouble(Cell::value).reversed()).toList();
        StringBuilder sb = new StringBuilder(head).append("整体为 ").append(num(overall)).append("；");
        int limit = Math.min(desc.size(), MAX_SHARE_ITEMS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("，");
            }
            sb.append(label(desc.get(i))).append(" ").append(num(desc.get(i).value()));
        }
        out.add(sb.append("。").toString());

        if (overall > 0) {
            List<Cell> biased = ratios.stream()
                    .filter(c -> Math.abs(c.value() - overall) / overall >= BIAS_THRESHOLD)
                    .sorted(Comparator.comparingDouble((Cell c) -> Math.abs(c.value() - overall)).reversed())
                    .limit(MAX_SHARE_ITEMS)
                    .toList();
            if (!biased.isEmpty()) {
                StringBuilder cmp = new StringBuilder();
                StringBuilder headline = new StringBuilder(unit)
                        .append("整体 ").append(num(overall)).append("，");
                for (int i = 0; i < biased.size(); i++) {
                    Cell c = biased.get(i);
                    double diff = (c.value() - overall) / overall;
                    if (i > 0) {
                        cmp.append("，");
                        headline.append("，");
                    }
                    cmp.append(label(c)).append(diff > 0 ? "高于整体 " : "低于整体 ").append(pct(Math.abs(diff)));
                    headline.append(labelPlus(label(c), diff > 0 ? "偏高 " : "偏低 ")).append(pct(Math.abs(diff)));
                }
                out.add(cmp.append("。").toString());
                verdicts.add(headline.toString());
            } else {
                // 都在阈值内也必须说话：「偏高还是偏低」是明确问句，
                // 沉默会让人以为这一问没答，而实际答案是「基本持平」
                out.add("各项与整体相差都在 " + pct(BIAS_THRESHOLD)
                        + " 以内，没有明显偏高或偏低的项，基本持平。");
                verdicts.add(unit + "整体 " + num(overall) + "，各项与整体相差都在 "
                        + pct(BIAS_THRESHOLD) + " 以内，基本持平");
            }
        }
    }

    /** 金额语义信号（列名或口径 SQL 里出现即认） */
    private static final String[] MONEY_SIGNALS = {"amount", "gmv", "price", "revenue"};

    /** 数量语义信号（含 uv：独立访客也是「数了多少个」，只是口径不同） */
    private static final String[] COUNT_SIGNALS = {"cnt", "count", "qty", "_num", "uv"};

    // ---------------------------------------------------------------- 缺口识别

    /**
     * 缺口识别：把「问题问到了什么」和「这次取回了什么」对照，差的部分明确说出来。
     *
     * <p>判据是<b>词面</b>而不是语义理解：问题里出现「金额」「单价」这类词，
     * 就去检查取回的指标里有没有金额类口径。这样做有两个好处 ——
     * 不需要理解句法，也不会误判成「模型觉得答完了」；代价是漏检（换了说法就认不出），
     * 但漏检只是少一条提示，不会产生错误结论，方向是安全的。
     */
    private List<String> detectGaps(List<Column> numeric, QueryIr ir,
                                    List<MetricDef> metricDefs, String question) {
        List<String> gaps = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return gaps;
        }
        String q = question;
        boolean wantMoney = containsAny(q, MONEY_WORDS);
        boolean wantCount = containsAny(q, COUNT_WORDS);
        boolean wantCompare = containsAny(q, COMPARE_WORDS);
        boolean wantTrend = containsAny(q, TREND_WORDS);

        // 原文写法给人看，小写副本用来做关键词匹配
        Set<String> takenRaw = new LinkedHashSet<>();
        Set<String> takenLower = new LinkedHashSet<>();
        if (ir != null && ir.getMetrics() != null) {
            for (MetricRef ref : ir.getMetrics()) {
                if (ref != null && ref.getMetricId() != null) {
                    String id = ref.getMetricId().trim();
                    takenRaw.add(id);
                    takenLower.add(id.toLowerCase(Locale.ROOT));
                }
            }
        }
        // 指标口径 SQL 也要看：metricId 可能叫 avg_order_value，字面不含 gmv 但口径是金额/数量
        String metricText = takenLower.stream()
                .map(id -> id + " " + metricSqlOf(id, metricDefs))
                .reduce("", (a, b) -> a + " " + b)
                .toLowerCase(Locale.ROOT);
        boolean hasMoney = containsAny(metricText, MONEY_SIGNALS);
        boolean hasCount = containsAny(metricText, COUNT_SIGNALS);

        String takenText = takenRaw.isEmpty() ? "（空）" : String.join("、", takenRaw);

        if (wantMoney && !hasMoney) {
            gaps.add("问题涉及金额口径，但本次只取回 " + takenText
                    + "，没有金额指标，所以金额类结论无法给出。");
        }
        if (wantCount && !hasCount) {
            gaps.add("问题涉及数量口径，但本次取回 " + takenText
                    + " 里没有数量指标，所以数量类结论无法给出。");
        }
        // 「件单价 / 客单价」= 金额 / 数量，两个都要有
        if (containsAny(q, new String[]{"单价", "价格"}) && !(hasMoney && hasCount)) {
            gaps.add("件单价／客单价 = 金额 ÷ 数量，需要金额与数量两个指标同时取回；本次取回 "
                    + takenText + "，缺" + (hasMoney ? "数量" : hasCount ? "金额" : "金额和数量")
                    + "，因此这一问无法计算。");
        }
        if (wantCompare && numeric.size() < 2) {
            gaps.add("问题要求两项量值互相对照，但本次只有一个数值指标（" + takenText
                    + "），没有可对照的第二项。");
        }
        // 「大盘 / 全站」这类基准词：派生比值算出来的是「本结果内部的整体值」，
        // 很容易被误当成「大盘口径」。两者不是一回事（大盘通常在另一张汇总表里），
        // 不点破的话，用户会以为「相对大盘偏高」这个判断已经做过了。
        if (containsAny(q, new String[]{"大盘", "全站", "全局"}) && hasMoneyAndCount(numeric, metricDefs)) {
            gaps.add("这里的「整体值」是按本次取回的指标算出来的合计比值，"
                    + "不是另一张表的大盘口径；要真正与大盘对比，需要把大盘指标一并取回。");
        }
        if (wantTrend && numeric.size() < 1) {
            gaps.add("问题涉及时间变化，但本次结果没有可比的数值列。");
        }
        return gaps;
    }

    /** 是否同时存在金额列与数量列（= 能算派生比值），用来判断「整体值」这个说法成不成立 */
    private boolean hasMoneyAndCount(List<Column> numeric, List<MetricDef> metricDefs) {
        boolean money = false;
        boolean count = false;
        for (Column c : numeric) {
            String sig = (c.key() + " " + metricSqlOf(c.key(), metricDefs)).toLowerCase(Locale.ROOT);
            money |= containsAny(sig, MONEY_SIGNALS);
            count |= containsAny(sig, COUNT_SIGNALS);
        }
        return money && count;
    }

    private String metricSqlOf(String metricId, List<MetricDef> metricDefs) {
        if (metricDefs == null || metricId == null) {
            return "";
        }
        for (MetricDef def : metricDefs) {
            if (def != null && def.getMetricId() != null
                    && def.getMetricId().trim().equalsIgnoreCase(metricId)) {
                return def.getMetricSql() == null ? "" : def.getMetricSql();
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- 列识别

    /** 一列数值：key 是结果集列名，display 是给人看的名字（优先指标中文名） */
    private record Column(String key, String display) {
    }

    private record Cell(String label, double value) {
    }

    private record DoubleSummary(double min, double max, double mean, double cv,
                                 Cell minCell, Cell maxCell) {
    }

    /** 找出所有「每行都是数字（允许 null）」的列 */
    private List<Column> numericColumns(List<Map<String, Object>> rows, List<MetricDef> metricDefs) {
        List<Column> out = new ArrayList<>();
        for (String key : rows.get(0).keySet()) {
            boolean allNumeric = true;
            for (Map<String, Object> row : rows) {
                Object v = row.get(key);
                if (v == null) {
                    continue;
                }
                if (!(v instanceof Number)) {
                    allNumeric = false;
                    break;
                }
            }
            if (allNumeric) {
                out.add(new Column(key, displayName(key, metricDefs)));
            }
        }
        return out;
    }

    /**
     * 指标中文名：结果列名就是 metricId，去 metric_def 里换成 metricName。
     * 换不到就原样显示 —— 宁可露一个英文 ID，也不猜它是什么意思。
     */
    private String displayName(String key, List<MetricDef> metricDefs) {
        if (metricDefs == null) {
            return key;
        }
        for (MetricDef def : metricDefs) {
            if (def == null || def.getMetricId() == null) {
                continue;
            }
            if (def.getMetricId().trim().equalsIgnoreCase(key)
                    && def.getMetricName() != null && !def.getMetricName().isBlank()) {
                return def.getMetricName().trim();
            }
        }
        return key;
    }

    /** 第一个非数值列 = 维度列（图表的 X 轴） */
    private String firstLabelKey(List<Map<String, Object>> rows, List<Column> numeric) {
        Set<String> numericKeys = new LinkedHashSet<>();
        numeric.forEach(c -> numericKeys.add(c.key()));
        for (String key : rows.get(0).keySet()) {
            if (!numericKeys.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private boolean isTemporal(List<Map<String, Object>> rows, String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.equals("dt") || lower.contains("date") || lower.contains("日期") || lower.contains("时间")) {
            return true;
        }
        Object v = rows.get(0).get(key);
        return v instanceof String s && s.matches("\\d{4}-\\d{1,2}-\\d{1,2}.*");
    }

    private List<Cell> cells(List<Map<String, Object>> rows, String labelKey, String valueKey) {
        List<Cell> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object v = row.get(valueKey);
            if (!(v instanceof Number n)) {
                continue;
            }
            String label = labelKey == null ? "" : String.valueOf(row.get(labelKey));
            out.add(new Cell(label, n.doubleValue()));
        }
        return out;
    }

    private DoubleSummary summarize(List<Cell> cells) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0;
        Cell minCell = null;
        Cell maxCell = null;
        for (Cell c : cells) {
            sum += c.value();
            if (c.value() < min) {
                min = c.value();
                minCell = c;
            }
            if (c.value() > max) {
                max = c.value();
                maxCell = c;
            }
        }
        double mean = sum / cells.size();
        double variance = 0;
        for (Cell c : cells) {
            variance += Math.pow(c.value() - mean, 2);
        }
        double sd = Math.sqrt(variance / cells.size());
        double cv = mean == 0 ? 0 : sd / Math.abs(mean);
        return new DoubleSummary(min, max, mean, cv, minCell, maxCell);
    }

    // ---------------------------------------------------------------- 小工具

    private String label(Cell c) {
        return c.label() == null || c.label().isBlank() || "null".equals(c.label()) ? "（空）" : c.label();
    }

    /**
     * 标签后面接中文时的空格规则：ASCII 开头（APP、4K显示器）补一个空格，
     * 中文开头不留 —— 否则会出现「APP为最大项」「电脑外设 为最大项」两种难看。
     */
    static String labelPlus(String label, String suffix) {
        boolean ascii = !label.isEmpty() && label.charAt(0) < 128;
        return label + (ascii ? " " : "") + suffix;
    }

    private boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private List<String> trim(List<String> findings) {
        return findings.size() <= MAX_FINDINGS ? findings : new ArrayList<>(findings.subList(0, MAX_FINDINGS));
    }

    /** 整数不带小数点，小数保留两位 —— 数字是给人读的，58.0 读起来像精度错误 */
    static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) {
            return String.valueOf((long) Math.rint(v));
        }
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static String pct(double v) {
        return BigDecimal.valueOf(v * 100).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    static String ratio(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
