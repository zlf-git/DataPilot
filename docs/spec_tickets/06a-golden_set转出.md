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
