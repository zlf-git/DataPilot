# Ticket 01c：stub IR 生成

## 目标
生成 15 条 `src/main/resources/stub_ir/q{n}.json`（question→IR 映射），供 StubLlmClient 返回。

## 背景
审计 P0#3/#4：01 说「15 条 stub_ir 由 Ticket 06 补」，但 06 拆成 06a/06b/06c 后三张票都没有这个任务，14 条桩 IR 是空气；且若塞在 06a（05 之后），03 的测试（打 Q4/Q5/Q6/Q11-Q13）会时序倒挂、没 stub 可用。故独立成 01c、前置到 03 之前。

## 修改范围
- 新增 `src/main/resources/stub_ir/q1..q15.json`

## 任务
1. 按 01 定义的基础 QueryIr 结构，为 15 题各写一条 stub IR（question 作 key，IR 作 value）
2. 简单题（Q1-Q5 单表）给完整 IR；需要 selectMode/timeRange/复合指标的题，先给「基础结构 + 绝对日期」，由 03/04/05 各自扩展时更新对应题
3. 15 个文件齐全，q1 由 01 已放好，本票补 q2-q15

## 验收标准
`src/main/resources/stub_ir/` 下 15 个 q{n}.json 文件齐全；每条含 question 与合法 QueryIr。

## 测试要求
脚本/单元断言 15 文件存在 + 每条 JSON 可反序列化为 QueryIr。

## 依赖
Ticket 01（基础 QueryIr 结构）

## 预估
0.5 天

## 不包含
- 完整 IR 结构（selectMode/timeRange/复合指标）——03/04/05 扩展后各自更新对应 stub
- golden_set.json（Ticket 06a）

## 交付后修订（QA-01c 回填，验收判据已升级）

原验收只看「IR 命中」，把 10 道算不出结果的题也算成通过（Q4/Q5/Q8/Q14 报「未实现」，Q6/Q7/Q11~Q13/Q15 静默返回全表汇总）。**「IR 命中 15/15」≠「15 题能答」，真实可用 5/15。** 判据修正如下，后续所有票的冒烟/评测沿用：

| 判据 | 定义 | 说明 |
|---|---|---|
| IR 命中 | IR 里 metrics 或 dimensions 非空 | 只证明桩匹配对 |
| 链路可用 | `sql` 非空 且 `answer` 为空 | 真的执行出数 |
| 答对 | 链路可用 且 结果值 == `qa/eval_expectations.json` 期望值 | 唯一有意义的指标 |
| 显式拒绝 | `sql` 为空 且 `answer` 含期望表标注的 blocker（Ticket 03/04/05） | 未实现语义必须走这条 |
| 静默错 | 链路可用但期望是 BLOCKED，或 SQL 缺 GROUP BY/ORDER BY/LIMIT/DISTINCT | **必须为 0** |

配套落地：
1. `qa/eval_expectations.json`：15 题期望状态表（OK / BLOCKED + blocker + requires + caliberPending），06b 的 run_eval 复用同一份，禁另抄一份。
2. `qa/e2e_stub.py`：按上表判定，输出「IR 命中 / 链路可用 / 答对 / 显式拒绝 / 静默错」五个数，静默错或预期不符即退出码 1。
3. `qa/verify_stub_ir.py`：白名单改为从 `GET /api/metrics` + `GET /api/metadata/fields` 取运行时真值，服务不可达退出码 2（拒绝用过时常量给假绿）。
4. `IrToSqlConverter` 失败契约：IR 含本票未实现的语义（明细/维度/排序/LIMIT/多指标）一律显式抛「未实现（Ticket xx）」，**禁止静默忽略**——静默丢维度会生成能跑但数值全错的全表汇总。
5. `StubLlmClient` 返回 IR 前深拷贝，防 03/05 在下游就地补字段导致跨请求串数据。
