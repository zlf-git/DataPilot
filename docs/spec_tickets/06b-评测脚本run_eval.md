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
