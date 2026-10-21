# QA-01c stub IR 生成 — 测试报告

## 1. 元信息

| 项 | 值 |
|---|---|
| 被测票号 | Ticket 01c（补 15 条 stub IR + 归一化匹配） |
| 被测版本 | commit `2afca19`（工作区 clean 时的 HEAD） |
| 测试时间 | 2026-10-21 11:10–11:18 |
| 运行方式 | `mvn -o clean compile` → `mvn -o spring-boot:run -Dserver.port=18080`（`datapilot.llm.provider=stub`） |
| JDK | JBR 21.0.7 |
| 依赖环境 | MySQL 3307（元数据 11 指标/28 字段/4 闸门参数）、StarRocks 9030（`mall_dw` 4 张 ADS 表） |
| 测试手段 | 只读黑盒：`qa/verify_stub_ir.py`、`qa/e2e_stub.py`、自建探针（打 `/api/ask`，不写库不改码） |
| 一句话结论 | **01c 的票面 DoD 全部达成**（15 文件齐全、可反序列化、白名单零偏差、15/15 命中无串题）；但端到端只有 **5/15** 题能算出正确值，其余 10 题要么报「无法识别」，要么**静默返回全表汇总**，且 e2e 脚本的通过判据掩盖了这一点 |

---

## 2. DoD 逐条判定

| # | 验收项（Ticket 01c） | 结果 | 实测证据 |
|---|---|---|---|
| 1 | `stub_ir/` 下 15 个 `q{n}.json` 齐全 | ✅ | 目录 15 个文件；`target/classes/stub_ir/` 同样 15 个（已打进编译产物） |
| 2 | 每条含 question + 合法 QueryIr | ✅ | 启动日志 `StubLlmClient 就绪，加载 15 条 stub IR`；Jackson 严格映射无一条告警（说明字段名与 QueryIr 完全对齐） |
| 3 | 所有 metricId 落 11 指标白名单 | ✅ | 实引用 8 个唯一 metricId（`gmv_total/gmv_channel/gmv_region/paid_order_cnt/order_cnt_region/pay_rate/pay_conversion_rate/sale_amount_product`）全部存在 |
| 4 | filter/dimension/orderBy 的 field 落 20 唯一字段白名单 | ✅ | 实引用 8 个唯一字段（`dt/channel_name/region/category_name/province_name/product_name/sale_cnt/total_gmv`）全部存在 |
| 5 | 15 道评测集原题逐字命中、无串题 | ✅ | 15/15 命中；stub 之间归一化后互相包含的对数 = **0** |
| 6 | 格式扰动变体（号→日/多空格/尾标点/全角） | ✅ | 5/5 命中，另补测全角整句、空格分隔亦命中 |
| 7 | 未命中兜底不 500 | ✅ | 「今天天气怎么样」→ 200 + `无法识别查询意图，请换个问法` |
| 8 | `mvn clean compile` | ✅ | EXIT=0，`target/classes/stub_ir` 15 个文件 |
| 9 | 已提交 | ✅ | `2afca19` 含 20 个文件（15 json + StubLlmClient + 2 脚本 + 2 份 QA 报告） |

### 白名单一项做了额外核对（脚本常量 vs 运行时真值）

`verify_stub_ir.py` 里的白名单是**手抄常量**，我把它和运行时 `/api/metrics`、`/api/metadata/fields` 的返回逐项比对：

| 维度 | 脚本常量 | 运行时真值 | 差异 |
|---|---|---|---|
| 指标 | 11 | 11 | 0（双向差集均为空） |
| 字段 | 20（28 行去重） | 20（28 行去重） | 0（双向差集均为空） |

本次**完全一致**，这条 DoD 成立。风险在于脚本不会随库变化（见 P1-4）。

---

## 3. 用例明细：15 题端到端实际行为

同一份 stub IR 打 `/api/ask`，响应里的 SQL / 结果与 `docs/评测集.md` 预期逐题对比：

| # | 预期值（评测集） | 实际返回 | SQL 特征 | 判定 |
|---|---|---|---|---|
| 1 | 53042.41 | **53042.41** | `SELECT SUM(total_gmv) … WHERE dt = ?` | ✅ |
| 2 | 69 | **69** | `SELECT SUM(paid_order_cnt) FROM ads_trade_overview_di`（无时间过滤） | ✅ |
| 3 | 15604.97 | **15604.97** | 双条件 `dt/channel_name` 均参数化 | ✅ |
| 4 | 江苏/上海/浙江 | `answer=无法识别查询意图`，sql=null | metrics=[] 被引擎拦 | ❌ 报错 |
| 5 | 4K显示器/机械键盘 | `answer=无法识别查询意图` | 同上 | ❌ 报错 |
| 6 | APP 0.1000 / 小程序 0.1000 / 网页 0.0750 | **单行 0.9** | `SELECT SUM(paid)/SUM(order) …`，无 GROUP BY | ❌ **静默错** |
| 7 | 上海 15695.05 | **53042.41** | 无 GROUP BY、`limit=1` 未生成 LIMIT | ❌ **静默错** |
| 8 | 4K显示器(4)/人体工学椅(3)/机械键盘(3) | `answer=无法识别查询意图` | metrics=[] | ❌ 报错 |
| 9 | 0.9 | **0.9** | `SUM(paid_order_cnt)/SUM(total_order_cnt)` | ✅ |
| 10 | 2 | **2** | `dt + region` 双条件参数化 | ✅ |
| 11 | APP 138821.73 | **390001.84**（7 天全渠道汇总） | 无 GROUP BY / 无 LIMIT | ❌ 静默错 |
| 12 | 华东 254466.85 | **390001.84** | 同上 | ❌ 静默错 |
| 13 | 电脑外设 293469.91 | **422204.53**（全类目汇总） | 同上 | ❌ 静默错 |
| 14 | 2026-10-16 66184.50 | `answer=无法识别查询意图` | metrics=[] | ❌ 报错 |
| 15 | APP 0.3832 / 网页 0.3226 / 小程序 0.2942 | **53042.41**（当天全渠道 GMV） | 无 GROUP BY，占比语义完全丢失 | ❌ 静默错 |

**归因（五类）**：4 题「模板缺失」（metrics 空的明细/DISTINCT 模式归 Ticket 03）；6 题「模板缺失」（dimensions/orderBy/limit 未进 SQL，同属 03）。**没有一题是 01c 的槽位错**——15 题 IR 的结构和取值都对得上评测集。

一处正向自洽校验：Q3 实际值 15604.97 ÷ Q1 53042.41 = **0.29420**，与 Q15 文档预期「小程序 0.2942」吻合，说明评测集预期值本身自洽。

### 匹配边界用例（补充 10 条）

| 用例 | 结果 |
|---|---|
| 前缀寒暄「请问10月19日当天的总GMV是多少」 | ✅ 命中 |
| 后缀寒暄「…是多少 帮我算一下」 | ✅ 命中 |
| 全角整句 | ✅ 命中 |
| 空格分隔「近7天 GMV 最高的渠道是哪个」 | ✅ 命中 |
| **双意向拼接**「Q1？Q11？」 | ⚠️ 只命中较长的 Q1，第二个问题**静默丢弃**，无任何提示 |
| 追问「…是多少，那10月18日呢」 | ⚠️ 只回 10-19，追问部分静默丢弃 |
| 去掉「是多少」的同义句「10月19日当天的总GMV」 | ❌ 未命中 |
| 渠道别名「10月19日app渠道的GMV是多少」 | ❌ 未命中 |
| 相对日期「今天总GMV是多少」 | ❌ 未命中 |
| 排序反转「按转化率从低到高排序」 | ❌ 未命中（**这个是对的**，语义已变不该命中） |

后三条 miss 属桩的既定边界（精确匹配桩），交付报告已声明；双意向静默丢弃是唯一需要留意的行为。

### 并发

15 题 × 3 轮 = 45 并发请求，与串行结果逐字段比对：**不一致 0 次**（traceId、ir、sql、rows 全部稳定）。

---

## 4. 缺陷清单

### P1-1 `e2e_stub.py` 的通过判据弱于真实行为，「15/15 通过」有误导性

`hit()` 判据是 `metrics 非空 or dimensions 非空`。Q4/Q5/Q8/Q14 的 `ir.dimensions` 非空 → 脚本判 OK，但同一响应的 `answer` 是「无法识别查询意图，请换个问法」，`sql=null`、`rows=[]`。

位置：`qa/e2e_stub.py` 的 `hit()`。
后果：跑完看到「命中 15/15」，会以为这 15 题都能答；实际可用 5 题。06b 若复用这个判据做冒烟，会得出错误的准备度结论。

### P1-2 Q15 的 IR 与「10月19日各渠道的GMV」完全同形，占比语义在 IR 里不存在

`q15.json` = `{metrics:[gmv_channel], dimensions:[channel_name], filters:[dt=2026-10-19]}`，和一道普通的「各渠道GMV」提问**逐字段相同**。`QueryIr` 当前只有 `question/metrics/dimensions/filters/orderBy/limit`，没有表达「分母来自另一张表」的字段。

后果：Ticket 03/05 无论怎么实现，都无法仅凭 IR 区分「各渠道GMV」和「各渠道GMV占比」，Q15 永远做不到。06b 里会被误归因为「模板缺失」或「口径错」，查错方向全错。

### P1-3 「近7天」在 5 条 stub IR 里完全没有落地

Q2/Q11/Q12/Q13/Q14 的 IR 既无 `timeRange` 也无时间过滤（如 `q11.json` 只有 metrics/dimensions/orderBy/limit）。当前这 5 题数值碰巧对（69、390001.84 等），**唯一原因是表里恰好只有 2026-10-13~10-19 七天数据**。

后果：数据一旦扩到 30 天，Q2 的 69、Q11 的 138821.73 全部偏离，而 stub 仍然「命中」——错误会全额记到 L6 口径错，真实原因（时间过滤缺失）被掩盖。Ticket 04 落地时必须回填这 5 条。

### P1-4 `verify_stub_ir.py` 的白名单是手抄常量，不读库也不读接口

脚本里 `METRIC_IDS` / `FIELD_NAMES` 是硬编码集合。本次与运行时真值零差异（11/20 全对），但库里新增或改名一个指标/字段，脚本不会跟着变，会继续给绿；反过来库里删了某个字段，脚本仍认为合法。

位置：`qa/verify_stub_ir.py` 第 16~34 行。
建议：改成先 `GET /api/metrics` + `GET /api/metadata/fields` 拉真值再比对（顺带把「白名单从哪来」这件事也对上了）。

### P2 级

| # | 问题 | 位置 |
|---|---|---|
| P2-1 | **静默错比报错更危险**：Q6/Q7/Q11/Q12/Q13/Q15 的 dimensions/orderBy/limit 被 converter 整段丢弃，SQL 少了 GROUP BY/LIMIT，返回全表汇总且 `answer=""`，看起来是成功。建议 03 未实现前，`dimensions` 或 `limit` 非空时显式抛「Ticket 03 未实现」，别静默降级 | `IrToSqlConverter.convert` |
| P2-2 | Q8 的 `orderBy.field` 用底层字段 `sale_cnt`，Q6/Q7/Q11/Q12/Q13 用 metricId，两套写法并存。03 的 IR schema 里要明确 `orderBy.field ∈ metricId ∪ 字段名`，否则实现时只会认其中一种 | `q6~q13.json` vs `q8.json` |
| P2-3 | `StubLlmClient` 把 entries 里**同一个 QueryIr 实例**直接返回给请求（`g.setIr(e.getIr())`）。45 并发实测无不一致（因为下游只读），但 03/05 一旦在下游就地补 `selectMode/timeRange`，共享实例会跨请求串数据 | `StubLlmClient.generate` 第 71 行 |
| P2-4 | 匹配是「prompt 包含 stub question」，不是精确相等：缺「是多少」、渠道别名 `app`、相对日期「今天」均 miss。桩的边界，但 06b 造变体集时必须保证只扰动格式不删词 | `StubLlmClient.normalize` + `contains` |
| P2-5 | 双意向拼接静默只取一个，无提示。当前可接受，接真 LLM 前建议明确「一次一问」约束 | `StubLlmClient.generate` |

---

## 5. 数据清理与还原

| 项 | 结果 |
|---|---|
| 是否写库 | 否。全程只调 `GET /api/metrics`、`GET /api/metadata/fields`、`POST /api/ask`（执行链路只读 StarRocks） |
| 元数据计数 | 启动自检 `metric_def=11 条`，与测试前一致 |
| 是否改被测代码 | 否，未修改任何文件 |
| 服务状态 | 已停止（PID 25848），18080 端口已释放 |
| 测试脚本落点 | `C:\Users\zlf\WorkBuddy\2026-10-18-18-31-24\qa\`（probe_01c.py / probe_01c_match.py / probe_01c_conc.py / cmp_whitelist.py），不属于被测仓库 |

### ⚠️ 测试期间工作区发生并发改动

测试开始时 `git status` 干净（HEAD `2afca19`）。测试结束复核时出现 9 处未提交改动，时间戳 11:14:40–11:17:22，**不是我产生的**：

- 新增 `agent/gate/`、`sql/SqlValidator.java`、`knowledge/OntologyCache.java`
- 删除 `agent/step/SqlValidateStep.java`
- 修改 `AgentEngine`、`AgentContext`、`AskController`、`pom.xml`、`deploy/seed.sql`

看起来是 Ticket 02（五道闸门）已开工。**本报告所有结论基于 `2afca19` 的编译产物**（JVM 11:14:22 启动时加载的类），不受这批改动影响。但当前工作区树已不等于被测树，重跑记得先确认基线。

---

## 6. 修复建议（按优先级）

1. **`e2e_stub.py` 的 `hit()` 加上「answer 为空且 sql 非空」**，并额外输出一行「链路可用率 5/15」，让脚本结论和真实能力对齐（当前这一行是最容易误判的地方）。
2. **Ticket 03 开工前先定 `orderBy.field` 与 `dimensions` 的契约**，并把 Q15 需要的占比表达（`ratio` 的分子/分母 metricId，或分子 metricId + 分母表）写进 IR schema——否则 Q15 结构性无解。
3. **Ticket 04 落地时回填 Q2/Q11/Q12/Q13/Q14 的 timeRange**，别等到 06b 跑出偏差再回头找。
4. **`verify_stub_ir.py` 改读 `/api/metrics` + `/api/metadata/fields`**，去掉手抄常量。
5. **converter 在 dimensions/limit 非空时显式报「Ticket 03 未实现」**，把静默错变成显式错，06b 的失败归因才准。
6. `StubLlmClient` 返回 IR 前做一次深拷贝，防 03/05 下游就地改 IR 造成跨请求污染。
