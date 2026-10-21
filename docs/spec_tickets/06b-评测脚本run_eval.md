# Ticket 06b：评测脚本 run_eval.py

## 目标
写 `eval/run_eval.py` 读 golden_set.json 逐题打 /api/ask 对拍，出六层指标。

## 背景
Spec §5.1/E3，80% 验收唯一可复现证明。

## 修改范围
- 新增 `eval/run_eval.py`

## 任务
1. 读 golden_set.json
2. 逐题打 /api/ask，收集 ir/sql/rows/gates/costMs
3. **按 `expect_type` 分支对拍**（不是比 SQL 文本——生成器用标量子查询、标准答案用 JOIN，写法不同但结果等价）：
   - `scalar`：数值 ±0.01
   - `stringset`：集合相等，忽略顺序
   - `rowset`：**只比行数 + 行集合（multiset）相等，不比列名、不比行序**〔06a 实测回填：原票面写「按位置的数值 ±0.01」，但 Q8 两轮独立执行采样到过 `人体工学椅/机械键盘` 顺序翻转（两行并列，GROUP BY 哈希序不稳），按位置比会误判 value_mismatch。列数用 golden_set 的 `expect_cols` 长度校验，列名不比——生成器输出别名是 metricId（`sale_cnt_product`），与标准 SQL 的 `total_paid`/`ratio` 本就不同（审计 P1#5）〕
   - 数值容差一律 `abs(a-b) <= 0.01`，`Decimal`/`None` 先归一
4. 窗口题（golden_set 里 `window` 非空，共 5 题 Q2/Q11~Q14）**除行级对拍外必须断言 SQL 形状**：`dt > ? AND dt <= ?`，全文不得出现 `BETWEEN`〔04 实测回填：2026-10-12 是空分区，BETWEEN 与半开区间逐题值相同，只比值无法判别〕
5. `probe_window3` 作为独立回归项单列（期望 `157702.85`；BETWEEN 写法会得 `223887.35`），不计入 15 题准确率，但为 FAIL 时整体结论不得判 PASS
6. 出六层指标 JSON（准确率/表命中率/字段命中率/平均延迟/语法通过率/闸门拦截率），另附逐题 `{q, pass, reason}`
7. 落盘 `result.json` 时同时写入 `golden_set_sha256` + 被测 `git rev-parse HEAD` + 未提交改动文件数，三者成对归档（06a 的期望表已带 sha256，缺了就无法回溯是哪版真源测的哪版代码）

## 验收标准
`python eval/run_eval.py --host http://localhost:8080` 跑出 result.json，含 15 题逐题 pass/fail，失败题带 reason（col_mismatch / value_mismatch / gate_blocked / empty_rows）。

## 测试要求
桩 LLM 下跑通：15 题全部返回非空 rows；对照 golden_set 出准确率。**12/15 是及格线不是目标**——Ticket 01-05 未完成时允许低于 12，但必须逐题记录失败原因；全链完成后复跑，实测值写入简历。

## 依赖
Ticket 06a

## 预估
0.5 天

## 不包含
- 真 LLM 评测（需 Key）

---

## 落地记录（2026-10-21）

**新增资产**

| 文件 | 说明 |
|---|---|
| `eval/run_eval.py` | 读 `golden_set.json` → 逐题打 `/api/ask` → 按 `expect_type` 对拍 → 六层指标 + 逐题 verdict → 落 `result.json`（含 golden_set sha256 + 被测 commit + 未提交改动数） |
| `eval/result.json` | 本轮基线产物（stub，18081），供「哪个真源测的哪版代码」回溯；每次跑覆盖 |

**对拍实现要点**
1. **rowset 一律集合比对**：行数一致 + 逐行匹配（用「已匹配行出队」的方式保证多重集语义），**不按行序**。行内比法分两档——实际列名集合与 `expect_cols` 一致时按列名比；不一致（生成器用 metricId 做别名，Q2 输出 `paid_order_cnt` 而标准 SQL 是 `total_paid`）时按**行内值多重集**比。列数用 `len(expect_cols)` 校验，列名不比。
2. **窗口题硬断言 SQL 形状**（5 题：Q2/Q11~Q14）：禁 `BETWEEN`、必须同时含 `dt > ?` 与 `dt <= ?`、参数必须含 from/anchor 且不含第三个值。`probe_window3` 单列回归项（期望 157702.85，若得 223887.35 直接点名「多吃了一天」），不计入 15 题准确率但 FAIL 即整体 FAIL。
3. **`sql_must` 只做软检查**：逐条子串命中算进 `sql_shape_score` 并在逐题里列 `sql_must_miss`，不判 FAIL——票面明确「标准 SQL 与生成器结果一致即可，写法可不同」，硬判会误杀等价写法（窗口形状断言不受此豁免）。
4. **题数护栏**：`--expect-total`（默认 15），golden_set 少题直接 `SystemExit`，防止「删掉难题就全绿」。
5. **绕过环境代理**：`build_opener(ProxyHandler({}))`。实测本机 `HTTP_PROXY` 会把 19999 的「连接被拒」伪装成 `502 upstream connect failed`，导致「服务没起」误报成「网关故障」。

**六层指标定义（写清口径，避免以后各说各话）**

| 指标 | 定义 | 本轮实测 |
|---|---|---|
| 准确率 | pass / 15 | 15/15 = 100% |
| 表命中率 | 实际 SQL 引用的表集合 == 标准 SQL 的表集合 | 15/15 = 100% |
| 字段命中率 | 两侧引用的**白名单物理字段**集合相等（抹掉 SELECT 的 `AS` 别名，只在 `ORDER BY/GROUP BY/HAVING` 段抹别名复用） | 15/15 = 100% |
| 平均延迟 | `/api/ask` 的 `costMs` 均值 | 19.3 ms（stub） |
| 语法通过率 | sql 非空 + 无拒绝文案（语法闸门未 REJECT 且执行出结果） | 15/15 = 100% |
| 闸门拦截率 | 任一闸门 REJECT 的题数占比（golden_set 全是有答案的题，正常 0） | 0/15 = 0% |

`字段命中率` 的抹别名规则踩了两次坑，值得记：① 不抹别名 → Q11/Q12 标准 SQL 的 `SUM(gmv) AS total_gmv` 里 `total_gmv` 是另一张表的真实字段，误判 13/15；② 全文抹别名 → Q2/Q10 的 `SUM(paid_order_cnt) AS paid_order_cnt` 别名与自身字段同名，真引用被一起吃掉，仍 13/15。分两步（SELECT 段抹声明、排序段抹复用）后两侧同规则才对。

**验收（18081，provider=stub，StarRocks 3.3.8-e3816ec，锚点 2026-10-19）**
- `python eval/run_eval.py --host http://127.0.0.1:18081` → **PASS**：15/15 全绿，六层指标见上表，`sql_shape_score 49/49`，窗口探针 PASS（157702.85）。
- `result.json` 归档字段齐全：`golden_set.sha256=6cfa7d9ee1c0…`、`generated_at=2026-10-21T13:08:13+08:00`、`code.git_head`、`code.dirty_files`、`env.health={13,28,4}`。
- 逐题行内比法：Q6/Q8 走「按列名」（别名恰与 expect_cols 同名），Q7/Q11~Q15 走「按值多重集」，Q1/Q2/Q3/Q9/Q10 为 scalar。

**变异测试（临时脚本，未入仓）8/8 符合预期**

| 用例 | 期望 | 实际 |
|---|---|---|
| 基线 | exit 0 / PASS | exit 0 / PASS |
| Q1 期望值改 1.0 | FAIL value_mismatch | 一致 |
| Q8 期望行序反转 | 仍 PASS（不按行序判） | 一致 |
| Q8 期望少一行 | FAIL row_count_mismatch | 一致 |
| Q6 类型 rowset→scalar | FAIL row_count_mismatch | 一致 |
| `BETWEEN` + 缺半开形状（直调 `window_issues`） | 检出 3 条；合规 SQL 0 条 | 一致 |
| 删掉 Q15 | exit 1 拒绝出结论 | 一致 |
| host 不可达 | exit 1 | 一致 |

**已知遗留**
- `表命中率`/`字段命中率` 是**文本级**代理指标，不是「语义命中」：字段命中率只比字段集合，不比聚合函数与过滤值（值对拍已覆盖）。三处口径写在脚本 docstring，后续若改判定口径需同步改这里。
- 真 LLM 未评（本票仍 stub）。`provider=deepseek` 时同一脚本直接可跑，只换 `--host`。
- 白名单 `/api/metadata/fields` 返回 28 行、20 个唯一字段名（跨表同名字段重复），去重后才做集合比对。
- 平均延迟 19.3 ms 是 stub + 本机 StarRocks 的数字，不代表真 LLM 链路，简历引用必须注明口径。

**06a-d 补丁后的重新归档（2026-10-21 14:05）**
- 06a-d 修完校验器后 `golden_set.json` 重新生成，sha256 由 `6cfa7d9ee1c0…` 变为 `655695b5435b0d83…`；`eval/result.json` 已按新真源重跑落盘，`golden_set.sha256` 与文件一致。
- 重跑结论不变：PASS，准确率/表命中/字段命中/语法通过 15/15、闸门拦截 0、`sql_shape_score` 49/49、窗口探针 157702.85、平均延迟 31.9 ms（stub）。
- `result.json` 的 `code.dirty_files` 改为**排除自身输出文件**：否则「跑评测 → 文件变脏 → dirty=1」恒为正，归档失去信息量。实测 `git status` 首行前导空格被 `git()` 的 strip 吃掉，路径不能按固定下标切，已按「状态 + 路径」解析。
- 归档值：`git_head=376173a`、`dirty_files=0`。
