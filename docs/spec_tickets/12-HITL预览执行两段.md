# Ticket 12：HITL（IR 预览/执行两段）

## 目标
preview/execute 两段契约，五钉住点全落地，span_type 与 status 区分清楚。

## 背景
来自 A6（Spec §3 阶段③）+ Spec §4.6。审计 P0#7：PREVIEW 是 status 不是 span_type，需区分。

## 修改范围
- controller：preview/execute
- 闸门：execute 重跑 1-4
- agent_step：status 增 PREVIEW、span_type=hitl

## 任务
1. /api/ask/preview 只校验不执行
2. /api/ask/execute 重跑闸门 1-4
3. 编辑范围收窄（只改 value/limit/orderBy.direction/dimensions）
4. 结构变更记 REPLAN（status）
5. agent_run.final_sql 存最终版；**agent_step.status 增 PREVIEW、span_type=hitl**（PREVIEW 是 status 不是 span_type，span_type 枚举来自 Ticket 09 的 hitl）

## 验收标准
preview 只校验、execute 重跑闸门、改 value 不产生注入；agent_step 记录 status=PREVIEW 且 span_type=hitl。

## 测试要求
集成测试：① preview 后改 filter value 再 execute，断言重跑闸门（可注入 value 被拦）；② 结构变更记 status=REPLAN 且 span_type=hitl。只测外部行为。

## 依赖
Ticket 02、03（完整生成器，preview 需生成真实 SQL）、09（hitl 枚举）

## 预估
1 天

## 不包含
- SQL 文本编辑入口（Spec 明确走 IR 路线不改 SQL）
