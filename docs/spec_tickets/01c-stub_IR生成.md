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
