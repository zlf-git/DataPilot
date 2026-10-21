# Ticket 06a：golden_set.json 转出

## 目标
从评测集.md 转出结构化 `eval/golden_set.json`，只做 expect_sql/expect_rows 实跑校验。

## 背景
Spec v2.1 §5.1/E3：题库以结构化 golden_set.json 为准，不解析 md 表格。审计 P0#4：stub IR 已独立成 01c 并前置，本票不再碰 stub IR，只做 expect_rows 实跑校验。

## 修改范围
- 新增 `eval/golden_set.json`

## 任务
1. 从评测集.md 提取 15 题：question / expect_sql / expect_cols / expect_rows / expect_value / **expect_type**
2. **`expect_type` 只允许三值**：`scalar`（Q1 的 `53042.41`、Q2 的 `69`）、`stringset`（Q4「江苏/上海/浙江」、Q5「4K显示器/机械键盘」）、`rowset`（Q11-Q15 多行多列）；`expect_value` 的形状必须与 type 匹配（scalar→数字/字符串、stringset→数组、rowset→对象数组）
3. 每题的 `expect_sql` 单独执行一遍，把真实结果写进 `expect_rows`，不手抄
4. 生成 `eval/golden_set.json`

## 验收标准
15 题字段齐全；每题 `expect_type ∈ {scalar, stringset, rowset}` 且 `expect_value` 形状匹配；15 条 `expect_sql` 逐条独立执行成功，`expect_rows` 与执行结果一致。

## 测试要求
json 校验（jq/脚本）15 条 + 字段完整。

## 依赖
Ticket 05（评测集口径定稿后转出）

## 预估
0.25 天

## 不包含
- run_eval.py（Ticket 06b）
- stub_ir（已挪 Ticket 01c）
- expect_ir（stub_ir/q{n}.json 本身就是每题期望 IR，不再在 golden_set 里重复）

---

## 落地记录（2026-10-21）

**新增资产**
| 文件 | 说明 |
|---|---|
| `eval/golden_set.json` | 15 题机读真源：question / expect_type / expect_sql / expect_cols / expect_rows / expect_value / difficulty / row_order_stable（+ 从 QA 期望表机械合并的 sql_must / window） |
| `eval/gen_golden_set.py` | 转出器：解析 `docs/评测集.md` 的 ```sql 代码块 → 15 条独立连接逐条执行 → 落盘。`--dry-run` 只校验不写盘 |
| `eval/check_golden_set.py` | 校验器：结构 + 形状 + `--replay` 重放对拍（证明 expect_rows 是实测不是手抄） |

**关键实现决定**
1. **expect_sql 解析自文档，不手抄**：正则抓 `**Q{n}**` 之后的 ```sql 块并剥掉 `--` 注释行。文档改了重跑即同步；文档缺题/重题直接报错退出。
2. **expect_rows 一律实测**：15 条各自 `pymysql.connect` → `select_db('mall_dw')` → 单条 execute，`Decimal`→float、`date`→`yyyy-MM-dd`，结果原样落盘。
3. **expect_type 双保险**：先按结果形状推断（1 行 1 列→scalar、多行 1 列→stringset、其余→rowset；1 行多列归 rowset，与票面 Q7 一致），再与脚本内声明的票面值交叉校验，不一致直接拒绝生成——防止数据变化后类型静默漂移、判定口径跟着变。
4. **与 `qa/eval_expectations.json` 交叉校验**：Ticket 03/04/05 人工核过的值（容差 0.011、集合语义）若与实测不符则拒绝生成，避免 golden_set 悄悄换值。两者分工：`eval_expectations.json` 继续服务 `qa/e2e_stub.py`（含 status/blocker/blame 等 QA 语义），`golden_set.json` 是评测真源（06b 只读它）；来源 sha256 记在 `sources` 字段。
5. **`VERSION()` 在 StarRocks 返回 5.1.0（MySQL 协议兼容号）不是内核版本**，改用 `current_version()` 取到 `3.3.8-e3816ec`，写进 `target.starrocks_version`。

**验收（2026-10-21，StarRocks 3.3.8-e3816ec，mall_dw 锚点 2026-10-19）**
- `python eval/gen_golden_set.py`：15/15 条 expect_sql 独立执行成功；15/15 与期望表一致；类型分布 scalar=5 / stringset=2 / rowset=8；锚点 2026-10-19；四表行数 7 / 21 / 35 / 21，dt 均 2026-10-13~2026-10-19。
- `python eval/check_golden_set.py --replay`：PASS（结构 15/15 + 形状匹配 + 重放一致 15/15）。
- 校验器变异测试 5 例（临时脚本，未入仓）：改 scalar 值 / 把 Q4 类型改 scalar / 删 Q15 / Q1 expect_sql 改错值 → 全部 exit=1 且报准确原因；stringset 打乱顺序 → exit=0（形状与顺序无关，符合判定口径）。

**落盘实测值（expect_value）**
Q1 53042.41 ｜ Q2 69 ｜ Q3 15604.97 ｜ Q4 [上海, 江苏, 浙江] ｜ Q5 [4K显示器, 机械键盘] ｜ Q6 3 行(APP 0.1 / 小程序 0.1 / 网页 0.075) ｜ Q7 上海 15695.05 ｜ Q8 3 行(4K显示器 4 / 人体工学椅 3 / 机械键盘 3) ｜ Q9 0.9 ｜ Q10 2 ｜ Q11 APP 138821.73 ｜ Q12 华东 254466.85 ｜ Q13 电脑外设 293469.91 ｜ Q14 2026-10-16 66184.5 ｜ Q15 APP 0.3832 / 网页 0.3226 / 小程序 0.2942。

**给 06b 的硬约束（本票实测证据支撑）**
1. **rowset 不能按位置判**：两次独立采样结果不同——首轮 **Q8 行序两轮不一致**（`人体工学椅 3` 与 `机械键盘 3` 并列，`GROUP BY product_name` 的哈希序不稳），次轮 15/15 全一致。同一份 SQL 两次采样得到不同结论，说明 Q8 的并列行序是**不确定行为**，本次恰好稳定不代表可依赖。06b 票面「按位置的数值 ±0.01」在 Q8 上迟早会误判 `value_mismatch`，必须走集合比对（与评测集 §5 一致）。
2. `expect_cols` 只是 expect_sql 的执行列名（如 Q2 `total_paid`、Q9 `pay_rate`、Q15 `ratio`），与 Agent 输出的 metricId 别名不同；06b 只能校验**列数**，不比列名。
3. 5 条窗口题（Q2/Q11/Q12/Q13/Q14）的 `window` 与 `sql_must` 已合并进 golden_set，06b 直接读，不要再从 eval_expectations.json 取第二份。
4. `probe_window3`（157702.85 vs 223887.35）不在 golden_set 的 15 题里，是独立的窗口口径探针，06b 需单列回归项。

**已知遗留**
- `row_order_stable` 只是两轮采样的证据字段，不是判据；出现 False 不影响任何题的 pass 判定。
- 真 LLM 评测不在本票（需 Key），golden_set 本身与 provider 无关。
