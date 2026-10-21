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
   - `rowset`：**只比行数 + 按位置的数值 ±0.01 / 字符串精确，不比列名**（审计 P1#5：Q8 生成器输出别名 `sale_cnt_product`，比列名必 col_mismatch）
4. 出六层指标 JSON（准确率/表命中率/字段命中率/平均延迟/语法通过率/闸门拦截率），另附逐题 `{q, pass, reason}`

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
