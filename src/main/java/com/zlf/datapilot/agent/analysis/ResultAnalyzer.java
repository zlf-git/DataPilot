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
import java.util.stream.Collectors;

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
     * 指标字典：本次召回的定义优先，回落到全量字典。
     *
     * <p>为什么要有第二层：召回是字面匹配（问题里出现指标名），而模型完全可能选一个
     * 字面没命中但语义正确的指标 —— 此时 recalled 里没有它，中文名就退化成英文 ID。
     * 分析层只是展示，不该被召回策略限制，所以全量字典做兜底。
     * 顺序上仍以 recalled 优先：它带的是本次召回的上下文口径，一致性更好。
     */
    private record Dict(List<MetricDef> recalled, Map<String, MetricDef> all) {

        MetricDef find(String metricId) {
            if (metricId == null) {
                return null;
            }
            String id = metricId.trim();
            if (recalled != null) {
                for (MetricDef d : recalled) {
                    if (d != null && d.getMetricId() != null && d.getMetricId().trim().equalsIgnoreCase(id)) {
                        return d;
                    }
                }
            }
            return all == null ? null : all.get(id.toLowerCase(Locale.ROOT));
        }

        String name(String metricId) {
            MetricDef d = find(metricId);
            if (d == null || d.getMetricName() == null || d.getMetricName().isBlank()) {
                return metricId;
            }
            return d.getMetricName().trim();
        }

        String sql(String metricId) {
            MetricDef d = find(metricId);
            return d == null || d.getMetricSql() == null ? "" : d.getMetricSql();
        }
    }

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
        return analyze(rows, ir, metricDefs, null, question);
    }

    /**
     * 带全量指标的入口：{@code allMetrics} 只用于展示名/口径兜底，不参与任何白名单判定 ——
     * 白名单在闸门那侧，这里放宽不会放松安全边界。
     *
     * @param allMetrics metric_id（小写）→ 定义，可为 null
     */
    public Analysis analyze(List<Map<String, Object>> rows, QueryIr ir,
                            List<MetricDef> metricDefs, Map<String, MetricDef> allMetrics, String question) {
        if (rows == null || rows.isEmpty()) {
            return Analysis.EMPTY;
        }
        Dict dict = new Dict(metricDefs, allMetrics);
        List<String> findings = new ArrayList<>();
        SummarySlot verdicts = new SummarySlot();
        List<Column> numeric = numericColumns(rows, dict);
        String labelKey = firstLabelKey(rows, numeric);

        if (numeric.isEmpty()) {
            // 纯文本结果（如明细 DISTINCT）：没有可算的量，不硬凑结论
            return Analysis.EMPTY;
        }
        if (labelKey == null) {
            // 没有维度列（单值结果、多指标各占一列）：占比、集中度、均值偏离全都无从谈起 ——
            // 硬算只能得到「n1合计 7；1 占 14.3%」这种没人读得懂的句子（Ticket 22 实测踩到）
            return new Analysis("", List.of(), detectGaps(numeric, ir, dict, question));
        }
        boolean temporal = isTemporal(rows, labelKey);
        // 两列份额对照放最前：「A 占比与 B 占比是否匹配」通常是问题的主句，
        // 分别列出两组占比让人自己心算，等于没回答问题
        boolean sharesCovered = addShareMismatchFindings(findings, rows, labelKey, numeric, verdicts);
        // 派生结论（金额÷数量 这类两列之间的比值）紧随其后：「件单价偏高还是偏低」往往是第二问
        addDerivedFindings(findings, rows, labelKey, numeric, dict, question, verdicts);
        // 份额对照只消费了前两列。Ticket 23 实测的教训：原来只要 sharesCovered 为真就跳过
        // <b>全部</b>列的分布分析，于是第三个指标（日均产出）取到了却一条结论都没有 ——
        // 「取数成功但整列被静默丢弃」是最难发现的错，因为它不报错、也不缺数。
        Set<String> consumed = sharesCovered
                ? Set.of(numeric.get(0).key(), numeric.get(1).key())
                : Set.of();
        // 若已经有一列是「份额拆分」（值合计≈100%），那么其他量值列的「占 X%」与它完全重复，
        // 只说绝对合计即可 —— 同一组百分比说两遍，正是 Ticket 22 用户嫌啰嗦的原因
        boolean shareColumnPresent = numeric.stream()
                .anyMatch(c -> c.kind() == Kind.RATIO && cells(rows, labelKey, c.key()).stream()
                        .mapToDouble(Cell::value).sum() >= 0.95
                        && cells(rows, labelKey, c.key()).stream().mapToDouble(Cell::value).sum() <= 1.05);
        for (Column col : numeric) {
            if (consumed.contains(col.key())) {
                continue;
            }
            if (temporal) {
                addTemporalFindings(findings, rows, labelKey, col, verdicts);
            } else if (col.kind() == Kind.RATIO) {
                addRatioFindings(findings, rows, labelKey, col, verdicts);
            } else if (col.kind() == Kind.AVERAGE) {
                addAverageFindings(findings, rows, labelKey, col, verdicts);
            } else {
                addDistributionFindings(findings, rows, labelKey, col,
                        numeric.size() > 1, shareColumnPresent, verdicts);
            }
        }
        List<String> gaps = detectGaps(numeric, ir, dict, question);
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
     * <p>上限 3 条：一个问句通常最多三问（Ticket 23 实测：「累计GMV、占大盘比、日均产出」
     * 就是三个分句，卡在 2 条会让第三问在顶部彻底消失）。再多就又是复述了。
     */
    private static final class SummarySlot {
        private final List<String> parts = new ArrayList<>();

        void add(String verdict) {
            if (verdict != null && !verdict.isBlank() && parts.size() < 3 && !parts.contains(verdict)) {
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
                                         boolean shareColumnPresent, SummarySlot verdicts) {
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
                sb.append(label(c)).append(" ").append(num(c.value()));
                // 已有独立的份额列（如「占大盘比」）时不再重复算占比：
                // 同一组百分比出现两遍，读者只会以为系统在凑字数
                if (!shareColumnPresent) {
                    sb.append(" 占 ").append(pct(c.value() / total));
                }
            }
            if (desc.size() > limit) {
                double rest = desc.subList(limit, desc.size()).stream().mapToDouble(Cell::value).sum();
                sb.append("，其余 ").append(desc.size() - limit).append(" 项合计")
                        .append(shareColumnPresent ? " " + num(rest) : "占 " + pct(rest / total));
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
            // 兜底判定：单列分布题没有更具体的主句时，用「合计 + 头部项」回答。
            // 有份额列时不进 verdicts：那句「XX 占 p%」与份额列的「XX 居首 p%」是同一个信息，
            // 顶部判断只留两句话的位置，不能被重复占用
            if (!shareColumnPresent) {
                verdicts.add(name + "合计 " + num(total) + "，"
                        + labelPlus(label(desc.get(0)), "为最大项") + "（占 " + pct(cr1) + "）");
            } else {
                // 只说规模，不提「谁最大」—— 「谁最大」由份额列那句负责，顶部只有两句位置
                verdicts.add(name + "合计 " + num(total));
            }
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

    // ---------------------------------------------------------------- 比例型（本身已是百分比）

    /**
     * 比例列：直接读值排序，<b>不做</b>合计与占比。
     *
     * <p>为什么单独一类：比例列自己就是「占了多少」，再拿它去除以列合计，等于用它除以它自己 ——
     * 结果永远是 100%，是正确但无信息的句子。实测踩过的正是这种：渠道 GMV 与「占大盘比」
     * 两列做份额对照，三项差额全是 0.0pp。
     *
     * <p>如果这些比例自身加起来接近 1，说明它是份额拆分，可以补一句「合计 100%」；
     * 如果不接近 1（各渠道各自的支付率），说明它们是互相独立的比率，
     * 那就只能横向比高低，绝不能说成构成。
     */
    private void addRatioFindings(List<String> out, List<Map<String, Object>> rows, String labelKey,
                                  Column col, SummarySlot verdicts) {
        List<Cell> cells = cells(rows, labelKey, col.key());
        if (cells.isEmpty()) {
            return;
        }
        String name = col.display();
        List<Cell> desc = cells.stream()
                .sorted(Comparator.comparingDouble(Cell::value).reversed()).toList();
        double sum = desc.stream().mapToDouble(Cell::value).sum();

        StringBuilder sb = new StringBuilder(name).append("：");
        int limit = Math.min(desc.size(), MAX_SHARE_ITEMS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("，");
            }
            sb.append(label(desc.get(i))).append(" ").append(pct(desc.get(i).value()));
        }
        out.add(sb.append("。").toString());

        // 合计≈1 才是份额拆分；否则是各维度独立的比率，不能讲构成
        if (!desc.isEmpty() && sum >= 0.95 && sum <= 1.05) {
            out.add(name + "各项合计 " + pct(sum) + "，是一份完整的份额拆分。");
        }

        if (desc.size() >= 2) {
            Cell top = desc.get(0);
            Cell bottom = desc.get(desc.size() - 1);
            // 全部相等时不能再说「最高 X、最低 Y，相差 0.0 个百分点」—— 那是句自相矛盾的话
            if (Math.abs(top.value() - bottom.value()) < 1e-6) {
                out.add(name + "各项均为 " + pct(top.value()) + "，彼此没有差异。");
                verdicts.add(suffixJoin(name, "各项均为 " + pct(top.value())));
                return;
            }
            double gapPp = (top.value() - bottom.value()) * 100;
            // 并列第一必须说出来（Ticket 23 实测：APP 与小程序都 10.0%，
            // 只说「APP 居首」等于把并列项吞掉，读者会以为小程序更低）
            List<Cell> ties = ties(desc, top.value());
            String topText = ties.size() > 1
                    ? "最高 " + ties.stream().map(this::label).collect(Collectors.joining("、"))
                            + " 并列（" + pct(top.value()) + "）"
                    : name + joinCn("最高", label(top)) + "（" + pct(top.value()) + "）";
            out.add(topText + "，"
                    + joinCn("最低", label(bottom)) + "（" + pct(bottom.value()) + "），相差 "
                    + oneDecimal(Math.abs(gapPp)) + " 个百分点。");
            String topVerdict = ties.size() > 1
                    ? ties.stream().map(this::label).collect(Collectors.joining("、"))
                            + " 并列居首（均 " + pct(top.value()) + "）"
                    : "以 " + label(top) + " 的 " + pct(top.value()) + " 居首";
            verdicts.add(suffixJoin(name, topVerdict
                    + "，" + labelPlus(label(bottom), "最低") + "（" + pct(bottom.value()) + "）"));
        } else {
            verdicts.add(suffixJoin(name, "为 " + pct(desc.get(0).value())));
        }
    }

    /**
     * 与给定值并列的项（按数值相等，容差取 0.1bp —— 比例列本身只有 4 位小数，
     * 用相对容差会在小数位噪声上误判并列）。
     */
    private List<Cell> ties(List<Cell> desc, double value) {
        return desc.stream()
                .filter(c -> Math.abs(c.value() - value) < 1e-6)
                .limit(MAX_SHARE_ITEMS)
                .toList();
    }

    // ---------------------------------------------------------------- 均值型（日均/均价）

    /**
     * 均值列：给水平与横向高低，<b>不求和</b>。
     *
     * <p>日均值相加没有业务含义（7 天的日均加起来不是 7 天总量，是总量的 1/7）。
     * 所以这里只做「各维度谁高谁低」，并以<b>这几项的算术平均</b>为参照 ——
     * 参照系的名字必须写清是「这几项的均值」，不能含糊成「大盘」：
     * 只取回了若干渠道时，它们的均值不等于全站大盘。
     */
    private void addAverageFindings(List<String> out, List<Map<String, Object>> rows, String labelKey,
                                    Column col, SummarySlot verdicts) {
        List<Cell> cells = cells(rows, labelKey, col.key());
        if (cells.isEmpty()) {
            return;
        }
        String name = col.display();
        List<Cell> desc = cells.stream()
                .sorted(Comparator.comparingDouble(Cell::value).reversed()).toList();
        double mean = desc.stream().mapToDouble(Cell::value).average().orElse(0);

        StringBuilder sb = new StringBuilder(name).append("：");
        int limit = Math.min(desc.size(), MAX_SHARE_ITEMS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("，");
            }
            sb.append(label(desc.get(i))).append(" ").append(num(desc.get(i).value()));
        }
        if (desc.size() > 1) {
            sb.append("；").append(desc.size()).append(" 项均值 ").append(num(mean));
        }
        out.add(sb.append("。").toString());

        if (desc.size() >= 2 && mean > 0) {
            Cell top = desc.get(0);
            Cell bottom = desc.get(desc.size() - 1);
            double topDiff = (top.value() - mean) / mean;
            double bottomDiff = (bottom.value() - mean) / mean;
            out.add(labelPlus(label(top), topDiff >= 0 ? "高于这几项均值 " : "低于这几项均值 ")
                    + pct(Math.abs(topDiff)) + "，"
                    + labelPlus(label(bottom), bottomDiff >= 0 ? "高于这几项均值 " : "低于这几项均值 ")
                    + pct(Math.abs(bottomDiff)) + "。");
            verdicts.add(name + " " + labelPlus(label(top), "最高") + " " + num(top.value())
                    + "，" + labelPlus(label(bottom), "最低") + " " + num(bottom.value()));
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
        // 只有两列都是可加和量值时，「份额是否匹配」才有意义（Ticket 23）。
        // 若第二列本身就是比例（如「占大盘比」），拿它跟量值比份额，两边算出来是同一个数，
        // 差额恒为 0.0pp —— 一句正确但零信息的话，还会把真正该说的话挤掉。
        if (first.kind() != Kind.SUM || second.kind() != Kind.SUM) {
            return false;
        }
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
                                    List<Column> numeric, Dict dict, String question,
                                    SummarySlot verdicts) {
        if (labelKey == null || numeric.size() < 2) {
            return;
        }
        Column money = null;
        Column count = null;
        for (Column c : numeric) {
            // 只有可加和量值才参与「金额 ÷ 数量」（Ticket 23）。
            // 曾经踩过：日均产出的口径是 SUM(gmv)/COUNT(dt)，SQL 里的 COUNT 让它被认成数量列，
            // 于是算出「渠道GMV ÷ 日均产出 = 4.44」—— 分子分母都是金额，结果是个天数，不是单价。
            // 比例列同理：比例÷金额没有业务含义。
            if (c.kind() != Kind.SUM) {
                continue;
            }
            // 列名和口径 SQL 一起看：指标叫 sale_amount_product 字面含 amount，但叫 avg_order_value 的只能靠口径认
            String sig = (c.key() + " " + dict.sql(c.key())).toLowerCase(Locale.ROOT);
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
                                    Dict dict, String question) {
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
                .map(id -> id + " " + dict.sql(id))
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
        //
        // 但如果本次已经取回了一列真正的「占比」指标（如「渠道GMV占比」），
        // 这句话就是误报 —— 用户问的「占大盘比」确实已经答了（Ticket 23 实测踩到：
        // 结论里明明给了 38.8%，却还挂着「没有大盘口径」的提示，自相矛盾）。
        boolean hasRatioColumn = numeric.stream().anyMatch(c -> c.kind() == Kind.RATIO);
        if (!hasRatioColumn && containsAny(q, new String[]{"大盘", "全站", "全局"})
                && hasMoneyAndCount(numeric, dict)) {
            gaps.add("这里的「整体值」是按本次取回的指标算出来的合计比值，"
                    + "不是另一张表的大盘口径；要真正与大盘对比，需要把大盘指标一并取回。");
        }
        if (wantTrend && numeric.size() < 1) {
            gaps.add("问题涉及时间变化，但本次结果没有可比的数值列。");
        }
        // 假设推演（「如果某渠道当天故障，会损失多少」）不是取数问题。
        // 这类问句必须显式说明答不了，否则用户会以为上面的数字就是答案 ——
        // 而「各渠道累计 GMV」与「某渠道挂掉损失的 GMV」是两个数（后者还取决于流量是否转移）。
        if (containsAny(q, new String[]{"损失", "故障", "宕机", "挂掉", "假设", "假如", "情景"})) {
            gaps.add("「如果某一渠道故障会损失多少」属于情景推演，本系统只回答已发生的事实数据，"
                    + "不做假设模拟；可以拿上面的分渠道数值自行估算，但那不是系统给出的结论。");
        }
        return gaps;
    }

    /** 是否同时存在金额列与数量列（= 能算派生比值），用来判断「整体值」这个说法成不成立 */
    private boolean hasMoneyAndCount(List<Column> numeric, Dict dict) {
        boolean money = false;
        boolean count = false;
        for (Column c : numeric) {
            String sig = (c.key() + " " + dict.sql(c.key())).toLowerCase(Locale.ROOT);
            money |= containsAny(sig, MONEY_SIGNALS);
            count |= containsAny(sig, COUNT_SIGNALS);
        }
        return money && count;
    }

    // ---------------------------------------------------------------- 列识别

    /** 一列数值：key 是结果集列名，display 是给人看的名字（优先指标中文名） */
    private record Column(String key, String display, Kind kind) {
    }

    /**
     * 数值列的口径类型。为什么必须区分（Ticket 23 实测）：一次查询可以带回三类量值，
     * 它们的运算规则完全不同，混着算就会得出正确但无意义的句子。
     * <ul>
     *   <li>{@link #SUM} 可加和 —— 合计、占比、集中度都成立；</li>
     *   <li>{@link #RATIO} 本身已是比例 —— 再做「占合计比」恒等于它自己
     *       （实测：渠道 GMV 与「占大盘比」两列做份额对照，最大差 0.0pp，纯废话）；</li>
     *   <li>{@link #AVERAGE} 是均值 —— 求和没有意义（日均值相加不等于期间总量）。</li>
     * </ul>
     */
    private enum Kind { SUM, RATIO, AVERAGE }

    private record Cell(String label, double value) {
    }

    private record DoubleSummary(double min, double max, double mean, double cv,
                                 Cell minCell, Cell maxCell) {
    }

    /** 找出所有「每行都是数字（允许 null）」的列 */
    private List<Column> numericColumns(List<Map<String, Object>> rows, Dict dict) {
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
                out.add(new Column(key, dict.name(key), kindOf(key, dict)));
            }
        }
        return out;
    }

    /** 列名里出现即认作比例口径 */
    private static final String[] RATIO_SIGNALS = {"ratio", "rate", "pct", "percent", "share"};

    /** 列名里出现即认作均值口径 */
    private static final String[] AVERAGE_SIGNALS = {"avg", "_mean", "average"};

    /**
     * 判定数值列的口径类型。判据只看两处：列名（也就是 metricId）与指标口径 SQL。
     *
     * <p><b>顺序有讲究</b>：先看列名里的 avg/mean，再看是否比例。
     * 「日均产出」的口径是 {@code SUM(gmv)/COUNT(dt)}，最外层也有除号 ——
     * 但它是均值不是比率（<b>均值可以再乘天数还原总量，比率乘什么都不还原</b>）。
     * 列名写了 avg 就以均值为准，否则会把日均当成百分比显示成 20000.0%。
     *
     * <p>判不出来的一律当 SUM：猜成 RATIO 会让本该加和的量值失去合计，
     * 猜成 AVERAGE 会让合计凭空消失。两类误判都会静默少算，比多做一次合计更危险。
     */
    private Kind kindOf(String key, Dict dict) {
        String id = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (containsAny(id, AVERAGE_SIGNALS)) {
            return Kind.AVERAGE;
        }
        if (containsAny(id, RATIO_SIGNALS) || hasTopLevelDivide(dict.sql(id))) {
            return Kind.RATIO;
        }
        return Kind.SUM;
    }

    /**
     * 口径 SQL 的最外层是不是「A / B」。只看括号外，避免把 {@code SUM(a/b)} 误判成比例 ——
     * 那仍然是一个可加和的量值。
     */
    static boolean hasTopLevelDivide(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '/' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 指标中文名：结果列名就是 metricId，去指标字典里换成 metricName。
     * 换不到就原样显示 —— 宁可露一个英文 ID，也不猜它是什么意思。
     */
    private String displayName(String key, Dict dict) {
        return dict.name(key);
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

    /**
     * 指标名（可能叫「渠道GMV占比」也可能叫 {@code gmv_channel_ratio}）后面接一句话。
     * 两侧任一是 ASCII 就补空格 —— 只按末字符判断会漏掉「支付转化率APP、小程序」这种：
     * 名字结尾是中文，但接下去的并列项以字母开头。
     */
    static String suffixJoin(String head, String suffix) {
        if (head.isEmpty() || suffix.isEmpty()) {
            return head + suffix;
        }
        boolean space = head.charAt(head.length() - 1) < 128 || suffix.charAt(0) < 128;
        return head + (space ? " " : "") + suffix;
    }

    /**
     * 「中文前缀 + 标签」的拼接：任一侧是 ASCII 才补空格。
     * 目标是让「最高 APP」与「最低小程序」都不会多出空格或粘在一起。
     */
    static String joinCn(String prefix, String label) {
        if (prefix.isEmpty() || label.isEmpty()) {
            return prefix + label;
        }
        boolean space = prefix.charAt(prefix.length() - 1) < 128 || label.charAt(0) < 128;
        return prefix + (space ? " " : "") + label;
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
