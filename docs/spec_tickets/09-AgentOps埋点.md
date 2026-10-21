# Ticket 09：AgentOps 埋点

## 目标
TraceStep 每步写 agent_step，收尾写 agent_run，GET /api/traces 可查。span_type 枚举补齐。

## 背景
来自 A1（Spec §3 阶段③）+ Spec §2.1 接口清单。审计 P0#7、P1#9：span_type 补 hitl，判据收紧。**本轮审计 P0#7：桩 LLM 不真耗 token，cost 会为 0——依赖 Ticket 01 的 StubLlmClient 返回固定 usage（in 500/out 100），保证本票 cost>0 判据在桩下可验。**

## 修改范围
- `agent/trace` 包
- AgentEngine 横切埋点
- TraceController

## 任务
1. TraceStep 横切每 Step 写 agent_step，**span_type 枚举 = operation/llm/sql/gate/hitl**（补 hitl，供 Ticket 12 用）
2. ask 收尾写 agent_run（token/cost/latency）
3. TraceController：GET /api/traces + /{traceId}

## 验收标准
一次问数后 GET /api/traces 查到 trace + span 明细：**step 数 ≥ 当前装配的 Step 数**（01 阶段 6 条起，全链 10 条），含 `span_type=llm` 与 `span_type=sql` 各至少 1，`cost > 0`、`latency_ms > 0`。

## 测试要求
集成测试：打 /api/ask 后查 /api/traces，断言 agent_run 存在 + agent_step 数 ≥ 当前装配 Step 数 + 含 llm/sql 各 1 + cost>0。**判据按实际链路动态取值、不写死 8**（01 交付时链路只有 6 Step，写死 8 会让本票当场失败）。只测外部行为。

## 依赖
Ticket 01

## 预估
0.5 天

## 不包含
- Langfuse 等重平台（Spec §6 真不做）
