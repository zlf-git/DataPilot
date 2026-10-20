# DataPilot LLM 集成（F-06 · Spring AI）

| 项 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 更新日期 | 2026-10-20 |
| 作者 | 张灵枫 |
| 关联文档 | Prompt模板.md（F-03）、IR_Schema.md（F-02）、执行模块.md（F-05） |

> F-06 用 Spring AI 替换仓库里手写的 LLM/Embedding 客户端，并落地结构化输出：ChatClient 调 LLM 生成 IR，`entity(QueryIr.class)` 强制反序列化。Embedding + Milvus few-shot 作为可插拔增强（`milvus.enabled=false` 时降级关键词召回）。

---

## 1. 依赖（pom.xml）

Spring Boot 3.4.8 对应 Spring AI 1.0.x，用 BOM 统一管理：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- OpenAI 兼容模型：DeepSeek（Chat）/ Qwen（Embedding）都走这个 starter -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <!-- Milvus 向量存储（few-shot 检索，可插拔） -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-vector-store-milvus</artifactId>
    </dependency>
</dependencies>
```

**替换清单**：删除手写 `DeepSeekLlmClient`、`QwenEmbeddingClient`、手写 `MilvusVectorStore`；保留 `MetadataRepository`、`Recaller`、`SqlExecutor`、`AgentContext`/`AgentStep` 接口。

---

## 2. ChatClient 配置（DeepSeek，OpenAI 兼容）

```yaml
spring:
  ai:
    openai:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        options:
          model: deepseek-chat
          temperature: 0        # 生成 SQL/IR 要确定性，不要发散
```

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder.defaultSystem(SYSTEM_PROMPT).build();
}
```

- `temperature=0`：Text2SQL 场景要确定性输出，避免同一问题两次生成不同 IR。
- `defaultSystem` 只放静态骨架；Ontology 白名单运行时拼装（见下）。

---

## 3. 结构化输出

LLM 直接落成 `QueryIr`，而非自由字符串：

```java
public QueryIr generate(String question, String ontologyPrompt, String fewShot) {
    return chatClient.prompt()
            .system(s -> s.text(ontologyPrompt))          // 运行时 Ontology + 输出规范
            .user(u -> u.text(USER_PROMPT)
                    .param("question", question)
                    .param("fewShot", fewShot))           // Milvus 召回或内置 3 条
            .call()
            .entity(QueryIr.class);                       // 强制反序列化为 IR
}
```

**要点**：`.entity(QueryIr.class)` 由 Spring AI `BeanOutputConverter` 驱动，把 LLM 返回的 JSON 强转 `QueryIr`；解析失败即抛错，进入降级/重试。

---

## 4. Embedding + Milvus（可插拔 few-shot）

### 4.1 配置

```yaml
spring:
  ai:
    openai:
      embedding:
        options:
          model: text-embedding-v3      # Qwen 嵌入，1024 维

datapilot:
  milvus:
    enabled: false                       # 先关，跑通主链路后再开
    host: localhost
    port: 19530
```

### 4.2 召回链

```java
public List<FewShotExample> recall(String question) {
    if (!milvus.enabled) return BUILTIN_FEW_SHOT;   // 降级：内置 3 条

    float[] vec = embeddingModel.embed(question);
    return vectorStore.similaritySearch(
        SearchRequest.query(question)
            .withTopK(3)
            .withSimilarityThreshold(0.75)
    );
}
```

- **可插拔**：`milvus.enabled=false` 时 `Recaller` 降级纯关键词召回 + 内置 few-shot，主链路不依赖 Milvus。
- **向量对象**：历史「问题 + 标准 IR」的 embedding，命中后把标准 IR 作为 few-shot 追加进 prompt。

---

## 5. 完整编排链（AgentEngine）

```
AskController.ask(question)
  → Recaller.recall(question)                     // 召回字段/指标/few-shot
  → PromptBuilder.build(question, recalled)       // 拼 Ontology system + user
  → chatClient.call().entity(QueryIr.class)       // LLM 生成 IR（结构化输出）
  → GateChain.check(ir)                            // 存在性 → 权限 → 代价
  → IrToSqlConverter.convert(ir)                   // IR → 受控 SQL（参数化）
  → 语法闸门（JSqlParser）
  → SqlExecutor.execute(sql)                       // StarRocks agent_ro
  → 结果自检
  → 返回
```

```java
public Answer ask(String question) {
    RecalledMetadata recalled = recaller.recall(question);     // ① 召回
    String prompt = promptBuilder.build(question, recalled);  // ② 组 Prompt
    QueryIr ir = llm.generate(question, prompt, recalled.fewShot()); // ③ 生成 IR
    gateChain.check(ir);                                       // ④ 前三道闸门
    SqlCommand cmd = converter.convert(ir);                    // ⑤ IR→SQL
    syntaxGate.check(cmd.sql());                               // ⑥ 语法闸门
    List<Map<String, Object>> rows = executor.execute(cmd);    // ⑦ 执行
    return resultGate.check(rows);                             // ⑧ 结果自检
}
```

---

## 6. 配置清单（application.yml）

```yaml
spring:
  ai:
    openai:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        options:
          model: deepseek-chat
          temperature: 0
  datasource:                                   # 元数据库（语义层）
    url: jdbc:mysql://localhost:3306/data_agent?useUnicode=true&characterEncoding=utf8
    username: root
    password: root

datapilot:
  engine:                                       # 业务数据引擎（StarRocks 只读）
    type: starrocks
    url: jdbc:mysql://localhost:9030/mall_dw?useUnicode=true&characterEncoding=utf8
    username: agent_ro
    password: AgentRo@2026
  milvus:
    enabled: false
    host: localhost
    port: 19530
```

---

## 7. 与手写骨架的迁移映射

| 仓库手写类 | 迁移到 Spring AI | 说明 |
|---|---|---|
| `DeepSeekLlmClient` | `ChatClient`（`spring-ai-starter-model-openai`） | OpenAI 兼容，指向 DeepSeek |
| `QwenEmbeddingClient` | `EmbeddingModel` | OpenAI 兼容，指向 DashScope |
| 手写 `MilvusVectorStore` | `MilvusVectorStore`（`spring-ai-starter-vector-store-milvus`） | 官方封装 |
| `Recaller` / `MetadataRepository` | 保留 | 召回逻辑不变，只换底层调用 |
| `SqlExecutor` | 保留 | 执行逻辑不变 |

---

## 8. 风险与降级

| 风险 | 降级策略 |
|---|---|
| LLM 返回非法 JSON | `.entity()` 抛错 → 重试 1 次 → 返回「请换个问法」 |
| LLM 超时 | `RestClient` 超时配置 + 熔断返回 |
| Milvus 未启动 | `milvus.enabled=false` 降级关键词召回 |
| 幻觉字段/表 | 存在性闸门拦截（F-04） |
