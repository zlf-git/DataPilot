# Ticket 10：多轮 session

## 目标
ask 加 sessionId，RewriteStep 补全指代，环比如实返回「前 7 天无数据」。

## 背景
来自 A2（Spec §3 阶段③）+ DoD4。

## 修改范围
- AskController：sessionId
- RewriteStep：多轮补全
- AgentContext：session 存储

## 任务
1. ask(question, sessionId) 签名
2. RewriteStep 取最近 2 轮 + 上轮 IR 摘要补全指代/时间
3. 环比场景如实返回「前 7 天无数据」

## 验收标准
「那环比呢」补全为「近 7 天 vs 前 7 天」，返回「前 7 天无数据」。

## 测试要求
集成测试：连续两问，断言第二问基于上下文补全 + 环比如实返回无数据。只测外部行为。

## 依赖
Ticket 01 + Ticket 04

## 预估
0.5 天

## 不包含
- 造 14 天数据让环比有真结果（Spec 明确 mall_dw 仅 7 天）
