# 被召回文档日志功能实施计划

| 项目     | 内容                                                                                   |
|--------|--------------------------------------------------------------------------------------|
| 工程     | `rag`（`D:\idea\rag-xmut\rag`）                                                        |
| 技术栈    | Spring Boot 4.1.1 / Spring AI 2.0.0 / Java 21 / Milvus / LM Studio                   |
| 文档状态   | 待评审（本文件仅为计划文档，**不含任何代码改动**）                                                          |
| 关联文档   | `rag/plan/multi-turn-chat-api-plan.md`、`rag/plan/multi-turn-chat-api-plan-status.md` |
| 关键前置说明 | **工程当前尚无检索（召回）代码**，本功能需与「多轮问答 RAG 增强」一并落地，详见第 2 节                                    |

---

## 1. 背景与目标

### 1.1 要解决的问题

RAG 系统一旦出问题，绝大多数情况不是模型坏了，而是**召回错了或没召回**：

- 用户问“宿舍几点熄灯”，检索回来的却是“军训服装要求”；
- `topK=4` 实际只召回 1 条，且相似度极低，但接口照样返回 200；
- 模型答非所问，却无法判断是「没召回」还是「召回了但没用好」。

当前工程对这些情况**完全不可观测**：既看不到召回了哪些切片，也看不到分数、命中条数与耗时。

### 1.2 目标

1. **可追溯**：任何一次问答，都能查出它当时召回了哪些文档切片、分数多少、来自哪个文件的第几片。
2. **可关联**：日志能与 `conversationId` 关联，多轮场景下能定位「第几轮、问的什么」。
3. **可判定**：明确记录「是否召回到」「是否把召回结果送进了提示词」「是否降级」，而不是只有成功/失败。
4. **可控成本**：日志体积可控、可开关、可滚动清理，且**默认不记录文档正文**（避免隐私泄露与磁盘膨胀）。
5. **不拖慢主链路**：日志写入不得成为问答请求的性能瓶颈，检索或日志异常都不能导致问答失败。

### 1.3 非目标

- 不做可视化大盘 / 前端页面（本期只做日志与查询接口）。
- 不做检索质量评估（recall@k、NDCG 等离线指标）——可作为后续独立课题。
- 不做模型侧埋点（token 数、模型耗时），本期只聚焦「被召回文档」。
- 不引入 Elasticsearch、Loki、Prometheus 等外部日志/观测基础设施。

### 1.4 术语约定

| 术语          | 含义                                          |
|-------------|---------------------------------------------|
| 召回 / 检索     | `VectorStore.similaritySearch(...)` 的调用过程   |
| 召回文档 / 命中切片 | 检索返回的 `List<Document>` 中的每一条                |
| 一次检索记录      | 一次 `similaritySearch` 调用产生的日志记录（一个 `logId`） |
| 命中          | 检索返回条数 > 0                                  |
| 送入提示词       | 命中切片被拼接进了本次 prompt，模型确实看到了                  |

---

## 2. 现状核对（实施前必读）

### 2.1 关键结论：目前“召回”这件事在代码里并不存在

已在当前源码中逐文件核对（UTF-8 实读）：

| 事实                                            | 证据                                                                                                                                           |
|-----------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| 全工程只有一处使用 `VectorStore`，且是**写入**而非检索          | `rag/src/main/java/com/wuyunbin/rag/service/DocumentService.java`（`vectorStore.add(chunks)`）                                                 |
| 多轮问答完全未接检索                                    | `rag/src/main/java/com/wuyunbin/rag/service/MultiTurnChatService.java` 仅做 `chatClient.prompt().user(...).param(CONVERSATION_ID, ...).call()` |
| `ragUsed` 字段已预留但恒为 `false`，`references` 恒为空列表 | 同上，`new MultiTurnChatResponse(conversationId, reply, round, false, List.of())`                                                               |
| 响应 DTO 已为召回结果预留结构                             | `rag/src/main/java/com/wuyunbin/rag/dto/MultiTurnChatResponse.java` 内部 `record Reference(String fileName, int chunkIndex, double score)`     |
| 已有 `RagRetriever` / 检索服务                      | **不存在**（`rag/src/main/java/com/wuyunbin/rag/` 下无此类）                                                                                          |
| 检索相关配置已预留                                     | `rag/src/main/resources/application.properties` 的 `rag.chat.rag.enabled` / `rag.chat.rag.top-k` / `rag.chat.rag.similarity-threshold`        |

**推论**：本功能不能“给现有检索加日志”，而应**把日志作为检索能力的组成部分一起设计**
——否则先做日志会得到一堆空记录，先做检索又会错过埋点时机。建议实施顺序：先落最小检索链路 → 同步落日志埋点（见第 6 节阶段
0/1）。

### 2.2 不要重复造轮子：Spring AI 已内置一个「召回内容日志」，但默认关闭且信息量不足

已在依赖字节码中确认：

- `MilvusVectorStore` 继承 `org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore`，*
  *每次 `similaritySearch` 都会自动产生 Micrometer Observation**。
- `spring-ai-autoconfigure-vector-store-observation:2.0.0`（已在 classpath）提供
  `VectorStoreQueryResponseObservationHandler`，它在观测结束时打印 `Vector Store Query Response: <文档正文...>`。
- 但其注册条件为
  `@ConditionalOnProperty(prefix="spring.ai.vectorstore.observations", name="log-query-response", havingValue="true")`，且
  `spring-configuration-metadata.json` 中 `defaultValue = false`。

也就是说，**零代码即可获得的基线能力**是：

```properties
spring.ai.vectorstore.observations.log-query-response=true
```

它的局限（正是本功能要补齐的部分）：

| 局限    | 说明                                                      |
|-------|---------------------------------------------------------|
| 只有正文  | 把切片正文整段打进日志，无文件名、无切片序号、无分数、无 docId                      |
| 无语义   | 只有一句 `Vector Store Query Response: ...`，没有查询、topK、阈值、耗时 |
| 无业务关联 | 拿不到 `conversationId`、轮次，无法与某次问答对应                       |
| 不可查询  | 只能翻日志文件，没有按会话/时间检索的能力                                   |
| 日志爆炸  | 长切片全文输出，单次问答可产生数 KB 日志，且默认无滚动上限策略                       |

> **决策**：把上述开关作为**排障兜底手段**写进文档（阶段 0 用它先验证“观测链路是通的”），主方案采用下面的**结构化业务日志**。

### 2.3 环境约束（影响选型）

| 约束                                                   | 证据                                                                                                                          | 影响                                                                 |
|------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| 未引入 Spring Data JPA / JDBC                           | `rag/pom.xml` 无相关依赖（虽然本机 `~/.m2` 存在 `spring-boot-starter-jdbc`、`starter-data-jpa`、`h2`）                                     | P2 落库可行但需改 `pom.xml`；P0/P1 应避免改依赖                                  |
| 无 `mvnw`、`PATH` 无 `mvn`，`PATH` 上 `java` 为 17（工程要 21） | 已在上一轮核实                                                                                                                     | 验证统一走 IntelliJ IDEA，或先补 Maven Wrapper                              |
| 日志实现为 Logback                                        | `spring-boot-starter-logging` 传递引入；`logback-classic` 1.5.x 在本机 `.m2`                                                        | 可用 `logback-spring.xml` 配置独立滚动文件                                   |
| 序列化栈为 Jackson 3                                      | 本机 `.m2` 中为 `tools/jackson/core/jackson-databind/3.1.5`（包名 `tools.jackson.databind`），另有 `spring-boot-starter-jackson` 4.1.1 | **不要**按 2.x 教程写 `com.fasterxml.jackson.databind.ObjectMapper`，包名已变 |

---

## 3. 日志数据设计（本功能的核心）

### 3.1 记录粒度

**一次 `similaritySearch` 调用 = 一条日志记录（一个 `logId`）**，命中切片作为该记录的 `hits` 数组内嵌，而不是“一个切片一条日志”。

理由：排查时的真实问题是「这次问答召回了什么」，数组结构天然对应；且便于统计 `hitCount`、整体耗时。

### 3.2 字段字典

| 分组  | 字段                    | 类型               | 必填 | 说明                                                        |
|-----|-----------------------|------------------|----|-----------------------------------------------------------|
| 标识  | `logId`               | string(UUID)     | 是  | 本次检索记录 ID，可与响应一起返回便于对账                                    |
| 标识  | `timestamp`           | string(ISO-8601) | 是  | 检索开始时间                                                    |
| 关联  | `conversationId`      | string           | 否  | 多轮会话 ID；单轮场景为 null                                        |
| 关联  | `round`               | int              | 否  | 第几轮问答                                                     |
| 关联  | `traceId`             | string           | 否  | 预留：后续引入 Micrometer Tracing 后填真实 traceId                   |
| 请求  | `query`               | string           | 是  | 实际送入检索的查询文本（当前 = 用户原始问题）                                  |
| 请求  | `queryLength`         | int              | 是  | 查询长度，便于发现超长查询                                             |
| 请求  | `topK`                | int              | 是  | 请求的召回条数                                                   |
| 请求  | `similarityThreshold` | double           | 是  | 请求的相似度阈值                                                  |
| 请求  | `filterExpression`    | string           | 否  | 元数据过滤表达式（本期通常为 null）                                      |
| 目标  | `collection`          | string           | 是  | 向量集合名（当前 `rag_docs`）                                      |
| 目标  | `database`            | string           | 是  | 库名（当前 `default`）                                          |
| 结果  | `hitCount`            | int              | 是  | 实际召回条数                                                    |
| 结果  | `latencyMs`           | long             | 是  | 检索耗时（毫秒）                                                  |
| 结果  | `outcome`             | enum             | 是  | `HIT`（有命中）/ `EMPTY`（无命中）/ `ERROR`（检索异常）/ `SKIPPED`（未启用检索） |
| 结果  | `usedInPrompt`        | boolean          | 是  | 命中结果是否被拼接进 prompt                                         |
| 结果  | `degraded`            | boolean          | 是  | 是否因异常降级为“无检索回答”                                           |
| 结果  | `errorMessage`        | string           | 否  | `outcome=ERROR` 时的异常摘要（**截断至 500 字符**）                    |
| 命中  | `hits[]`              | array            | 是  | 见下表，`EMPTY` 时为空数组                                         |
| 命中项 | `rank`                | int              | 是  | 排名，从 1 开始                                                 |
| 命中项 | `docId`               | string           | 是  | `Document#getId()`（Milvus 主键）                             |
| 命中项 | `score`               | double           | 是  | `Document#getScore()`，语义见 3.4                             |
| 命中项 | `fileName`            | string           | 否  | `metadata.file_name`                                      |
| 命中项 | `docMd5`              | string           | 否  | `metadata.doc_md5`                                        |
| 命中项 | `chunkIndex`          | int              | 否  | `metadata.chunk_index`                                    |
| 命中项 | `chunkTotal`          | int              | 否  | `metadata.chunk_total`                                    |
| 命中项 | `textLength`          | int              | 是  | 切片正文字符数（用于判断“是不是整段塞进去了”）                                  |
| 命中项 | `textPreview`         | string           | 否  | 正文前缀，**默认关闭**，开启后按 `previewChars` 截断                      |

元数据键名已在本工程确认：`file_name`、`doc_md5`、`chunk_index`、`chunk_total`（见 `SemanticTextSplitter` 与
`DocumentService`），因此命中项字段可直接映射，不会出现“字段对不上”的问题。

### 3.3 日志样例（JSONL，单行）

```json
{"logId":"7c1f0b3e-2f4a-4a2e-9a6f-1d0c9b8e5a11","timestamp":"2026-09-10T15:04:22.318+08:00","conversationId":"3f2a9c1e-8b44-4f0a-9d21-6c9b7a1f0e33","round":2,"query":"宿舍晚上几点熄灯","queryLength":8,"topK":4,"similarityThreshold":0.5,"filterExpression":null,"collection":"rag_docs","database":"default","hitCount":3,"latencyMs":87,"outcome":"HIT","usedInPrompt":true,"degraded":false,"errorMessage":null,"hits":[{"rank":1,"docId":"4611686018427388001","score":0.8123,"fileName":"jmu新生手册.txt","docMd5":"9f2c...e1","chunkIndex":42,"chunkTotal":149,"textLength":318},{"rank":2,"docId":"4611686018427388002","score":0.7710,"fileName":"jmu新生手册.txt","docMd5":"9f2c...e1","chunkIndex":43,"chunkTotal":149,"textLength":276},{"rank":3,"docId":"4611686018427388009","score":0.5231,"fileName":"jmu新生手册.txt","docMd5":"9f2c...e1","chunkIndex":12,"chunkTotal":149,"textLength":401}]}
```

### 3.4 待确认项：`score` 的语义

`MilvusVectorStore` 内部通过 `SearchParam.withMetricType(MetricType)` 使用度量类型，并映射为
`VectorStoreSimilarityMetric`（字节码中已确认存在 `COSINE` 常量与 `MetricType → VectorStoreSimilarityMetric` 映射表）。

- 若 collection 索引为 **COSINE**：score 越大越相似，`similarityThreshold` 是“下限”。
- 若为 **L2**：距离越小越相似，阈值语义相反。

**实施前必须确认**（阶段 0 的一个动作）：查询 Milvus 中 `rag_docs` 的索引度量类型，并把结论写进日志字段说明与文档，避免后续排查时把“分数低”误读成“召回差”。若度量不是统一的
COSINE，日志中应额外输出 `similarityMetric` 字段。

### 3.5 单一数据源原则（重要）

同一次检索产生的数据要同时服务于两处：

1. 接口响应 `MultiTurnChatResponse.references`（`fileName` / `chunkIndex` / `score`）；
2. 日志记录 `hits[]`。

**做法**：先构造一个内部模型（如 `RetrievalHit`），再由它**同时**映射出响应 `Reference` 与日志 `hits[]`，禁止两条路径各自从
`Document` 里 `getMetadata()`。否则后续很容易出现“响应里的 score 和日志里的对不上”的低级问题。

---

## 4. 方案选型

| 方案                          | 实现方式                                                            | 优点                             | 缺点                                                                   | 结论                 |
|-----------------------------|-----------------------------------------------------------------|--------------------------------|----------------------------------------------------------------------|--------------------|
| A. 内置观测日志                   | 打开 `spring.ai.vectorstore.observations.log-query-response=true` | 零代码，改配置即可                      | 只有正文、无分数/无关联、默认关闭、体积不可控                                              | **仅作阶段 0 的链路验证手段** |
| B. 结构化业务日志（落文件）             | 检索服务里构造记录对象 → 专用 logger 输出 JSONL → Logback 滚动文件                 | 无新依赖、字段完全可控、可开关、体积可控、性能好       | 查询靠 grep / 需自建解析                                                     | **P0 推荐**          |
| C. 结构化日志 + 内存环形缓冲 + 查询接口    | 在 B 之上加 `RetrievalLogStore`（有界队列）与 REST 查询                      | 可在 Knife4j 里直接查、无需登录服务器        | 只保留最近 N 条、重启即失                                                       | **P1 推荐**          |
| D. 落库（JDBC/JPA）             | 建 `retrieval_log` 表持久化                                          | 可长期分析、可 SQL 统计                 | 需改 `pom.xml`、建表、引入事务；与当前极简风格不符                                       | **P2 可选**          |
| E. 自定义 `ObservationHandler` | 实现 `ObservationHandler<VectorStoreObservationContext>`          | 自动覆盖所有 `VectorStore` 检索入口，扩展性好 | 拿不到 `conversationId` 等业务上下文；`getQueryResponse()` 可能为 null；需正确处理上下文类型 | P2 作为兜底补充          |

### 4.1 选型结论

- **P0**：方案 B（显式业务日志 + JSONL 滚动文件 + 专用 logger）。
- **P1**：方案 C（内存环形缓冲 + `GET /api/retrieval-logs` 查询接口）。
- **P2**：方案 D 或 E，按需选择。

### 4.2 为什么不首选方案 E（自定义 ObservationHandler）

虽然 `MilvusVectorStore` 已经自动产生观测，看似“最优雅”，但：

- 观测上下文里**没有业务信息**（`conversationId`、轮次），这正是排查多轮问答时最需要的信息；
- 需要在 `ObservationHandler` 里做上下文类型判断，并处理 `AUTO_STARTED` / `STARTED` / `STOPPED` 等阶段，复杂度高于收益；
- 一旦以后出现非 `VectorStore` 的检索入口（例如自建 HTTP 调 Milvus、或换用检索 API），观测埋点未必覆盖。

因此主方案选择在**业务代码里显式埋点**，天然拥有完整上下文，也更容易写单元测试。

---

## 5. 输出与查询设计

### 5.1 日志输出通道（P0）

| 项          | 设计                                                                 |
|------------|--------------------------------------------------------------------|
| logger 名称  | `rag.retrieval`（固定名称，禁止直接用类名 logger）                               |
| additivity | `false`（不重复打印到根 logger，避免双份日志）                                     |
| 文件         | `logs/retrieval.jsonl`                                             |
| 滚动策略       | 按天 + 按大小（单文件上限 50MB），保留 7 天，总上限 500MB                              |
| 异步         | 使用 Logback `AsyncAppender`（队列深度 1024，`discardingThreshold=0`，不丢日志） |
| 格式         | 纯 `%msg%n`，消息体为第 3 节定义的 JSON 单行，**不要**混入时间戳/级别前缀（时间戳已在 JSON 内）     |
| 开关         | `rag.retrieval.log.enabled`（默认 `true`），关闭时不构造记录、不写日志               |
| 正文         | `rag.retrieval.log.preview-chars`（默认 `0` = 不记录正文）                  |

配置文件位置：`rag/src/main/resources/logback-spring.xml`（目前工程**没有**该文件，属新增）。

> 若担心引入 `logback-spring.xml` 造成影响面扩大，可用最小替代：仅用 `logging.file.name` + 默认 pattern。但那样无法把
`rag.retrieval` 单独拆到文件，也不便于滚动控制，**不推荐**。

### 5.2 查询接口（P1）

| 方法    | 路径                                             | 说明                            |
|-------|------------------------------------------------|-------------------------------|
| `GET` | `/api/retrieval-logs?conversationId=&limit=50` | 倒序返回最近的检索记录，支持按会话过滤           |
| `GET` | `/api/retrieval-logs/{logId}`                  | 按 `logId` 查单条（内存缓冲未命中时返回 404） |

响应形态：

```json
{
  "total": 2,
  "items": [ { "logId": "...", "conversationId": "...", "query": "...", "hitCount": 3, "outcome": "HIT", "hits": [ ... ] } ]
}
```

约束：

- `limit` 范围 [1, 200]，默认 50；
- 缓冲为**有界**结构（`rag.retrieval.buffer-size`，默认 500，超出丢弃最旧记录）；
- 缓冲内容仅存内存，重启即失——文档中必须写明这一限制，避免使用者误解为“审计日志”。

### 5.3 与响应 DTO 的关系（P1）

建议在 `MultiTurnChatResponse` 中**新增一个可选字段** `retrievalLogId`，把“本次问答”与“本次检索日志”用 ID 串起来（对排查体验提升明显）。

注意：这属于对既有响应 DTO 的**增量字段**，`record` 增加组件会改变构造函数签名，需同步修改
`MultiTurnChatService` 中的构造调用；对 JSON 接口是向后兼容的（新增字段）。若评审时倾向“零改动既有 DTO”，可退化为：把
`logId` 仅写入日志、由 `conversationId + 时间窗` 反查。
---

## 6. 详细实施步骤

> 建议新增独立子包 `com.wuyunbin.rag.retrieval`，把「检索 + 检索日志」内聚在一起，避免继续往 `service` 里堆类。

### 阶段 0：前置——落地最小检索链路（约 0.5 人天，**不可跳过**）

本阶段对应 `multi-turn-chat-api-plan.md` 的「阶段 7（P1）」，是日志功能的前提。

1. 新增 `com/wuyunbin/rag/retrieval/RagRetriever.java`：

   ```java
   @Component
   @RequiredArgsConstructor
   public class RagRetriever {
       private final VectorStore vectorStore;

       public List<Document> retrieve(String query, int topK, double threshold) {
           return vectorStore.similaritySearch(SearchRequest.builder()
                   .query(query).topK(topK).similarityThreshold(threshold).build());
       }
   }
   ```

2. 在 `MultiTurnChatService` 中按 `ragEnabled` 决定是否调用，并把 `MultiTurnChatResponse.references` 由 `List.of()`
   改为真实映射（该项 DTO 已就绪）。
3. **确认 `score` 语义**：查询 `rag_docs` 的索引度量类型（COSINE / L2 / IP），把结论写入第 3.4 节所要求的位置。这是后续所有排查结论的地基。
4. 用 Milvus 中已有的 `jmu新生手册.txt`（约 149 条切片）跑通一次真实召回。

**完成标准**：调用一次多轮问答（`ragEnabled=true`），响应中 `ragUsed=true` 且 `references` 非空、`score` 合理。

### 阶段 1：日志数据模型（约 0.5 人天）

新增文件：

| 文件                                  | 说明                                                           |
|-------------------------------------|--------------------------------------------------------------|
| `retrieval/RetrievalHit.java`       | `record`，字段同第 3.2 节「命中项」                                     |
| `retrieval/RetrievalLogRecord.java` | `record`，字段同第 3.2 节除 `hits` 外的主干 + `List<RetrievalHit> hits` |

代码骨架：

```java
public record RetrievalHit(int rank, String docId, double score, String fileName,
                           String docMd5, Integer chunkIndex, Integer chunkTotal,
                           int textLength, String textPreview) {

    public static RetrievalHit from(int rank, Document doc, int previewChars) {
        Map<String, Object> md = doc.getMetadata();
        String text = doc.getText() == null ? "" : doc.getText();
        String preview = previewChars > 0
                ? text.substring(0, Math.min(previewChars, text.length()))
                : null;
        return new RetrievalHit(rank, doc.getId(), doc.getScore() == null ? 0.0 : doc.getScore(),
                str(md.get("file_name")), str(md.get("doc_md5")),
                intOrNull(md.get("chunk_index")), intOrNull(md.get("chunk_total")),
                text.length(), preview);
    }
}
```

**注意点**

- `Document#getScore()` 返回 `Double`，可能为 `null`（例如非检索路径产生的 `Document`），必须空值兜底，否则日志序列化会 NPE。
- 元数据取值统一走 `String.valueOf` + 空值判断的私有方法，**不要**直接强转 `(String) md.get(...)`，Milvus 元数据类型可能不是
  `String`。
- `RetrievalHit.from(...)` 是**唯一**的映射入口（第 3.5 节单一数据源原则）：响应 `Reference` 也从它转换，例如
  `new Reference(hit.fileName(), hit.chunkIndex(), hit.score())`。

**完成标准**：单元测试覆盖：正常切片、`getScore()` 为 null、元数据缺字段、`previewChars=0` 与 `>0` 两种情况。

### 阶段 2：日志写入器与 Logback 配置（约 0.5 人天）

1. 新增 `com/wuyunbin/rag/retrieval/RetrievalLogWriter.java`：

   ```java
   @Component
   @RequiredArgsConstructor
   public class RetrievalLogWriter {

       private static final Logger RETRIEVAL_LOG = LoggerFactory.getLogger("rag.retrieval");

       private final JsonMapper jsonMapper;   // Jackson 3：tools.jackson.databind.json.JsonMapper
       private final RetrievalLogProperties properties;

       public void write(RetrievalLogRecord record) {
           if (!properties.logEnabled()) {
               return;
           }
           try {
               RETRIEVAL_LOG.info(jsonMapper.writeValueAsString(record));
           } catch (RuntimeException e) {
               // 日志失败绝不影响主链路
               log.warn("检索日志写入失败 logId={}", record.logId(), e);
           }
       }
   }
   ```

   **注意点**：Jackson 3 中 `JacksonException` 继承自 `RuntimeException`（已在本机 `jackson-core:3.1.5` 字节码中确认），因此
   **不要**写 `catch (JsonProcessingException e)`，那是 2.x 的受检异常写法；也**不要**用
   `com.fasterxml.jackson.databind.ObjectMapper`（工程里没有该版本可用）。Spring Boot 4 自动装配的是 Jackson 3 的
   `JsonMapper`。

2. 拷贝 `logRecord` 中的 `logId` 到 MDC 或在失败日志中透出（可选）。

3. 新增 `rag/src/main/resources/logback-spring.xml`：

   ```xml
   <configuration>
     <appender name="RETRIEVAL_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
       <file>logs/retrieval.jsonl</file>
       <encoder><pattern>%msg%n</pattern></encoder>
       <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
         <fileNamePattern>logs/retrieval.%d{yyyy-MM-dd}.%i.jsonl</fileNamePattern>
         <maxFileSize>50MB</maxFileSize>
         <maxHistory>7</maxHistory>
         <totalSizeCap>500MB</totalSizeCap>
       </rollingPolicy>
     </appender>

     <appender name="RETRIEVAL_ASYNC" class="ch.qos.logback.classic.AsyncAppender">
       <appender-ref ref="RETRIEVAL_FILE"/>
       <queueSize>1024</queueSize>
       <discardingThreshold>0</discardingThreshold>
     </appender>

     <logger name="rag.retrieval" level="INFO" additivity="false">
       <appender-ref ref="RETRIEVAL_ASYNC"/>
     </logger>

     <include resource="org/springframework/boot/logging/logback/base.xml"/>
     <root level="INFO"><appender-ref ref="CONSOLE"/></root>
   </configuration>
   ```

   **注意点**：`base.xml` 的 include 用来保留 Spring Boot 默认的控制台行为；若 include 顺序或 `CONSOLE` appender
   名称引发启动报错，可退化为只声明 `rag.retrieval` logger、其余完全交给默认配置（用 `spring-boot` 的
   `<include resource="org/springframework/boot/logging/logback/defaults.xml"/>` + `console-appender.xml`）。

4. **异步不丢日志**：`discardingThreshold=0` 是关键，否则队列满时 Logback 会静默丢弃 `INFO` 级别日志——对一个“排查用”日志来说是致命的。

**完成标准**：启动应用后 `logs/retrieval.jsonl` 被创建；手工用 `RETRIEVAL_LOG.info("{\"test\":1}")` 验证格式为纯 JSON
单行、无前缀。

### 阶段 3：埋点接入（约 1 人天）

在 `MultiTurnChatService`（或把检索编排抽到 `RetrievalService`）中接入，埋点必须覆盖**四条路径**：

| 路径    | `outcome` | `usedInPrompt` | `degraded` |
|-------|-----------|----------------|------------|
| 未启用检索 | `SKIPPED` | false          | false      |
| 检索有命中 | `HIT`     | true           | false      |
| 检索无命中 | `EMPTY`   | false          | false      |
| 检索抛异常 | `ERROR`   | false          | true       |

代码骨架（示意，注意计时与 try/catch 位置）：

```java
boolean ragEnabled = resolveRagEnabled(request);
RetrievalLogRecord record;
String reply;

if (!ragEnabled) {
    record = RetrievalLogRecord.skipped(conversationId, round, request.message());
    reply = callModel(conversationId, request.message(), null);
} else {
    long start = System.nanoTime();
    List<Document> docs;
    try {
        docs = ragRetriever.retrieve(request.message(), topK, threshold);
    } catch (RuntimeException e) {
        // 降级：检索失败不能拖垮问答
        record = RetrievalLogRecord.error(conversationId, round, request.message(),
                topK, threshold, System.nanoTime() - start, e);
        return finish(record, callModel(conversationId, request.message(), null), false);
    }
    long latencyMs = (System.nanoTime() - start) / 1_000_000;
    List<RetrievalHit> hits = toHits(docs, previewChars);
    boolean used = !hits.isEmpty();
    record = RetrievalLogRecord.of(conversationId, round, request.message(), topK, threshold,
            latencyMs, hits, used);
    reply = callModel(conversationId, request.message(), used ? buildContext(docs) : null);
}

retrievalLogWriter.write(record);
```

**注意点**

- **计时范围**：`latencyMs` 只统计 `retrieve(...)`，不要把拼 prompt 和调模型算进去，否则耗时数字失去意义。
- **降级语义**：Milvus 不可用时必须走 `degraded=true` 继续回答，**不能**因为日志功能让问答 500。这与既有
  `DocumentController` 的“检索失败即报错”策略不同，需在评审时确认。
- **`usedInPrompt` 的真实含义**：有命中 ≠ 送进了 prompt（比如命中条数超过上限被裁剪、或全部低于阈值）。本期简便起见以「最终拼进
  prompt 的切片数 > 0」为准。
- **写入时机**：`retrievalLogWriter.write(record)` 放在方法末尾（含异常分支），保证“每次都有一条记录”，包括失败。
- **不要在检索循环里逐条写日志**，一次检索只写一行。

**完成标准**：四条路径各手工触发一次，`logs/retrieval.jsonl` 中出现 4 条 `outcome` 正确的记录；`EMPTY` 记录 `hits` 为空数组而非
`null`。

### 阶段 4：配置项（约 0.2 人天）

新增 `com/wuyunbin/rag/retrieval/RetrievalLogProperties.java`，用 `@Value`（与 `ChatConfig` 现有风格一致，避免引入
`@ConfigurationProperties` 所需的额外装配）：

```java
@Component
public class RetrievalLogProperties {
    @Value("${rag.retrieval.log.enabled:true}")          private boolean logEnabled;
    @Value("${rag.retrieval.log.preview-chars:0}")       private int previewChars;
    @Value("${rag.retrieval.buffer-size:500}")           private int bufferSize;
    // getter/setter 或 Lombok @Data
}
```

**完成标准**：`rag.retrieval.log.enabled=false` 时不产生任何 `rag.retrieval` 日志行。

### 阶段 5：内存缓冲与查询接口（P1，约 1 人天）

1. 新增 `retrieval/RetrievalLogStore.java`（接口）+ `retrieval/InMemoryRetrievalLogStore.java`（有界实现）：

   ```java
   @Component
   @RequiredArgsConstructor
   public class InMemoryRetrievalLogStore implements RetrievalLogStore {
       private final Deque<RetrievalLogRecord> buffer = new ConcurrentLinkedDeque<>();
       private final RetrievalLogProperties properties;

       @Override
       public void save(RetrievalLogRecord record) {
           buffer.addFirst(record);
           while (buffer.size() > properties.getBufferSize()) {
               buffer.pollLast();
           }
       }
   }
   ```

2. 在 `RetrievalLogWriter` 中同时 `store.save(record)`（或由调用方分别调用，二选一，保持单一职责一致）。
3. 新增 `controller/RetrievalLogController.java`，实现 5.2 节的两个接口。
4. 查询过滤在内存中完成：按 `conversationId` 过滤 + `limit` 截断 + 倒序（`addFirst` 已保证）。

**完成标准**：Knife4j 中可见两个接口；用真实 `conversationId` 查询能拿到对应记录；`limit` 越界返回 400。

### 阶段 6（P2，可选）：自定义 ObservationHandler 兜底

实现 `ObservationHandler<VectorStoreObservationContext>`，在 `onStop` 阶段取 `context.getQueryResponse()`（
`List<Document>`，可能为 `null`），生成“无业务上下文”的兜底记录，仅用于捕获**绕过 `RagRetriever` 的检索调用**。

需注意：`supportsContext` 必须判断 `context instanceof VectorStoreObservationContext`，且要处理 `QUERY` 之外的 `DELETE`
操作（`Operation` 枚举含 `QUERY`、`DELETE`）。

### 阶段 7（P2，可选）：持久化落库

若确需长期留存与 SQL 统计：

1. `pom.xml` 增加 `spring-boot-starter-jdbc`（本机 `~/.m2` 已存在，离线可行）与数据库驱动。
2. 建表 `retrieval_log`（主干字段）与 `retrieval_log_hit`（命中明细，一对多）。
3. 新增 `JdbcRetrievalLogStore` 实现同一 `RetrievalLogStore` 接口，靠配置切换。
4. **注意**：写库必须异步或容忍失败，绝不能因数据库抖动导致问答失败；建议沿用阶段 2 的“日志失败只告警”原则。

### 阶段 8：测试（约 1 人天）

见第 8 节。

### 阶段 9：文档与收尾（约 0.5 人天）

1. 更新 `rag/plan/multi-turn-chat-api-plan-status.md`，同步 P1（RAG）进度与本次新增能力。
2. 在工程 README 或 `rag/plan/` 下补充《检索日志字段说明》，至少写清 `score` 语义与 `logs/retrieval.jsonl` 的位置、滚动与保留策略。
3. 明确对外声明：内存缓冲**不是**审计日志，重启即失。

---

## 7. 配置项汇总

在 `rag/src/main/resources/application.properties` 追加：

```properties
# ============ 被召回文档日志 ============
# 是否记录检索日志（关闭后不构造、不写盘）
rag.retrieval.log.enabled=true
# 正文预览字符数；0 表示不记录正文（推荐，避免隐私泄露与日志膨胀）
rag.retrieval.log.preview-chars=0
# 内存查询缓冲容量（P1），超出丢弃最旧记录
rag.retrieval.buffer-size=500

# ============ 可选：Spring AI 内置召回内容日志（排障兜底，默认关闭） ============
# 打开后会把召回切片的完整正文打到日志，仅用于临时排障，生产务必保持 false
spring.ai.vectorstore.observations.log-query-response=false
```

| 配置项                                                     | 默认值          | 说明                            |
|---------------------------------------------------------|--------------|-------------------------------|
| `rag.retrieval.log.enabled`                             | `true`       | 检索日志总开关                       |
| `rag.retrieval.log.preview-chars`                       | `0`          | 正文预览长度，0 = 不记录正文              |
| `rag.retrieval.buffer-size`                             | `500`        | 内存缓冲条数上限（P1）                  |
| `spring.ai.vectorstore.observations.log-query-response` | `false`      | Spring AI 内置召回正文日志，仅排障时开启     |
| `rag.chat.rag.enabled`                                  | `false`（已存在） | RAG 总开关，关闭时日志只出现 `SKIPPED` 记录 |
| `rag.chat.rag.top-k`                                    | `4`（已存在）     | 召回条数                          |
| `rag.chat.rag.similarity-threshold`                     | `0.5`（已存在）   | 相似度阈值                         |

---

## 8. 测试计划

### 8.1 单元测试（不依赖 Milvus / LM Studio）

| 用例                                       | 断言                     |
|------------------------------------------|------------------------|
| `RetrievalHit.from` 正常切片                 | 字段完整映射，`rank` 从 1 递增   |
| `Document#getScore()` 为 null             | `score=0.0`，不抛 NPE     |
| 元数据缺 `file_name` / `chunk_index`         | 对应字段为 null，不抛异常        |
| `previewChars=0`                         | `textPreview=null`     |
| `previewChars=100` 且正文 500 字             | `textPreview` 长度恰为 100 |
| `RetrievalLogWriter` 在 `enabled=false` 时 | 不产生日志行                 |
| `RetrievalLogWriter` 序列化异常时              | 只告警，不向调用方抛异常           |

### 8.2 集成测试（`@SpringBootTest` + `@MockitoBean`）

用 `@MockitoBean VectorStore` 替掉真实 Milvus，`@MockitoBean ChatModel` 替掉真实模型（Spring Framework 7 中新注解为
`org.springframework.test.context.bean.override.mockito.MockitoBean`，本机 `spring-test:7.0.9` 已确认存在；不要用已废弃的
`@MockBean`）。

| 用例                                           | 断言                                                                                    |
|----------------------------------------------|---------------------------------------------------------------------------------------|
| `ragEnabled=true` 且 mock 返回 3 条 `Document`   | 响应 `ragUsed=true`、`references` 长度 3；日志记录 `hitCount=3`、`outcome=HIT`                   |
| mock 返回空列表                                   | `ragUsed=false`、`references` 为空；日志 `outcome=EMPTY`、`hits=[]`                          |
| mock 抛 `RuntimeException`                    | 接口仍返回 200（降级成功）；日志 `outcome=ERROR`、`degraded=true`、`errorMessage` 非空且 ≤ 500 字符        |
| `ragEnabled=false`                           | 日志 `outcome=SKIPPED`，且**从未**调用 `vectorStore.similaritySearch`（`verify(..., never())`） |
| 响应 `references[i].score` 与日志 `hits[i].score` | 完全一致（验证单一数据源原则）                                                                       |
| 缓冲超容量                                        | 写入 `bufferSize+10` 条后，查询接口只返回 `bufferSize` 条且为最新的                                     |

### 8.3 手工验收

见附录 B。

### 8.4 性能关注

- 单次检索的日志写入应为**纯内存构造 + 异步落盘**；用 `latencyMs` 观察开启/关闭日志前后检索耗时的差异，预期差异 < 5ms。
- 若发现明显抖动，优先检查 `AsyncAppender` 是否被 `discardingThreshold` 默认值影响。

---

## 9. 验收清单

- [ ] 触发一次有多轮上下文的检索，日志记录中含正确的 `conversationId` 与 `round`
- [ ] `hits[]` 字段齐全：`rank` / `docId` / `score` / `fileName` / `docMd5` / `chunkIndex` / `chunkTotal` / `textLength`
- [ ] 响应 `references` 与日志 `hits[]` 的 `score`、`fileName`、`chunkIndex` 完全一致
- [ ] 无命中时 `outcome=EMPTY`、`hits=[]`、`hitCount=0`
- [ ] 检索异常时 `outcome=ERROR`、`degraded=true`，且**问答接口仍正常返回**
- [ ] 未启用检索时 `outcome=SKIPPED`，且不调用 `similaritySearch`
- [ ] 默认配置下日志中**不含**文档正文（`textPreview` 为 null）
- [ ] `rag.retrieval.log.enabled=false` 后不再产生 `rag.retrieval` 日志
- [ ] `logs/retrieval.jsonl` 为一行一条合法 JSON，可被 `jq` 正常解析
- [ ] 滚动与保留策略生效（`maxFileSize` / `maxHistory` / `totalSizeCap` 已配置）
- [ ] （P1）`GET /api/retrieval-logs?conversationId=...` 能查到对应记录
- [ ] （P1）缓冲超容量时最旧记录被淘汰，接口不报错
- [ ] 单元测试与集成测试通过

---

## 10. 风险与对策

### 10.1 前置依赖未完成（高）

- 风险：检索能力尚不存在（第 2.1 节），若先做日志会得到空链路，验收无从谈起。
- 对策：严格按阶段 0 → 阶段 1 顺序推进；阶段 0 的完成标准是“响应 `references` 非空”，必须在日志开发前达成。

### 10.2 日志泄露文档正文（高）

- 风险：`VectorStoreQueryResponseObservationHandler` 内置行为会把切片正文整段打印；自定义实现若误开 `preview-chars` 同样会泄露。
- 对策：`preview-chars` 默认 `0`；`spring.ai.vectorstore.observations.log-query-response` 默认保持 `false`
  并写入文档警告；字段白名单化（只记列在字段字典里的字段），禁止把整个 `Document` 对象直接序列化。

### 10.3 日志体积不可控（中）

- 风险：高频问答 + 大 `topK` 会让 JSONL 迅速膨胀。
- 对策：JSONL 单行只存摘要（`textLength` 而非正文）；配置滚动与 `totalSizeCap`；提供总开关；后续可加采样率。

### 10.4 异步丢日志（中）

- 风险：Logback `AsyncAppender` 默认 `discardingThreshold=20`，队列达 80% 时会丢弃 `INFO` 及以下日志。
- 对策：显式设置 `discardingThreshold=0`；并在日志说明中标注该配置的用途。

### 10.5 `score` 语义误读（中）

- 风险：COSINE 与 L2 的“越大越好 / 越小越好”相反，误读会导致错误结论。
- 对策：阶段 0 确认度量类型并写入文档；必要时在日志中补充 `similarityMetric` 字段。

### 10.6 序列化栈混淆（中）

- 风险：工程为 Jackson 3（`tools.jackson.*`），网上示例多为 Jackson 2（`com.fasterxml.jackson.*`），容易写出编译不过或行为不符的代码。
- 对策：统一使用 `tools.jackson.databind.json.JsonMapper`；异常均为非受检（`JacksonException extends RuntimeException`
  ，已核实），不要按 2.x 的受检异常写法处理。

### 10.7 改动面外溢（中）

- 风险：为日志给 `MultiTurnChatResponse` 增加 `retrievalLogId` 会改变 `record` 构造签名，需要同步修改调用点。
- 对策：该项列为 P1 且标注为“可选”；若评审倾向最小改动，改为不带 `logId`、用 `conversationId + 时间窗` 反查。

### 10.8 检索降级策略与既有风格不一致（低）

- 风险：`DocumentController` 对异常是“返回 400/500”，而本功能主张“检索失败降级为普通回答”。
- 对策：在评审时明确二者语义不同：导入是**写操作**（失败必须让用户知道），检索是**读操作**（失败应尽量保住问答可用性）。

### 10.9 其余工程环境风险（沿用既有结论）

- 无 `mvnw`、`PATH` 无 `mvn`、`PATH` 上 `java` 为 17（工程要 21）；`rag/run-arg.txt` 拼装的 classpath 存在 `slf4j-simple` 与
  `logback` 冲突。
- 对策：验证走 IntelliJ IDEA；不要复用 `run-arg.txt`；新增 `logback-spring.xml` 后**务必重启应用**再验证（Logback 配置不会热生效）。

---

## 11. 里程碑与工作量

| 阶段 | 内容                                            | 优先级 | 预估           |
|----|-----------------------------------------------|-----|--------------|
| 0  | 最小检索链路 + 确认 `score` 语义                        | 前置  | 0.5 人天       |
| 1  | 日志数据模型（`RetrievalHit` / `RetrievalLogRecord`） | P0  | 0.5 人天       |
| 2  | 日志写入器 + `logback-spring.xml`                  | P0  | 0.5 人天       |
| 3  | 埋点接入（四条路径）                                    | P0  | 1 人天         |
| 4  | 配置项                                           | P0  | 0.2 人天       |
| 5  | 内存缓冲 + 查询接口                                   | P1  | 1 人天         |
| 6  | 自定义 `ObservationHandler` 兜底                   | P2  | 0.5 人天       |
| 7  | 持久化落库                                         | P2  | 1.5 人天       |
| 8  | 测试                                            | P0  | 1 人天         |
| 9  | 文档与收尾                                         | P0  | 0.5 人天       |
| —  | **P0 合计（阶段 0-4、8-9）**                         |     | **约 4.2 人天** |
| —  | **含 P1 合计**                                   |     | **约 5.2 人天** |
| —  | **含 P1+P2 合计**                                |     | **约 7.2 人天** |

建议提交拆分（便于回滚与评审）：

1. `feat(rag): 多轮问答接入 Milvus 检索`（阶段 0）
2. `feat(rag): 新增被召回文档结构化日志`（阶段 1-4、8-9）
3. `feat(rag): 提供检索日志查询接口`（阶段 5）
4. `feat(rag): 检索日志落库`（阶段 7，可选）

---

## 12. 回滚与兼容

**兼容性**

- 主链路是**新增**：新增包 `com.wuyunbin.rag.retrieval`、新增 `logback-spring.xml`、新增配置项。
- 对既有接口：`/api/chat`、`/api/chat/multi-turn`、`/api/documents` 的路径与语义不变；只有 P1 的 `retrievalLogId` 会为
  `MultiTurnChatResponse` 增加字段（JSON 层面向后兼容）。
- 数据：不改 Milvus schema，不删改既有向量；P0/P1 不新增 Maven 依赖。

**回滚步骤**

1. 删除新增文件：`retrieval/` 包、`RetrievalLogController`、`logback-spring.xml`。
2. 还原 `MultiTurnChatService`（移除埋点与检索调用）与 `application.properties`（移除新增配置段）。
3. 若已做阶段 7，删除 `retrieval_log*` 表并回退 `pom.xml`。
4. 删除 `logs/retrieval*.jsonl`。

**特别说明**：`logback-spring.xml` 是回滚时最容易遗漏的一项——若只删代码不删该文件，日志仍会尝试写 `logs/retrieval.jsonl`
（只是没有内容），容易造成“回滚不彻底”的错觉。

---

## 附录 A：已核实的相关 API 与事实（本机依赖）

| 结论                                                                                                                                                  | 来源                                                                                                  |
|-----------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `MilvusVectorStore` 继承 `AbstractObservationVectorStore`，每次 `similaritySearch` 自动产生观测                                                                | `spring-ai-milvus-store-2.0.0.jar` 字节码                                                              |
| 内置 `VectorStoreQueryResponseObservationHandler` 打印 `Vector Store Query Response: <正文>`                                                              | `spring-ai-vector-store-2.0.0.jar`                                                                  |
| 内置日志默认**关闭**，开关 `spring.ai.vectorstore.observations.log-query-response`，`defaultValue=false`                                                        | `spring-ai-autoconfigure-vector-store-observation-2.0.0.jar` 的 `spring-configuration-metadata.json` |
| `VectorStoreObservationContext` 可获取 `getQueryRequest()` / `getQueryResponse()`（`List<Document>`）/ `getCollectionName()` / `getSimilarityMetric()` 等 | 同上                                                                                                  |
| `VectorStoreObservationContext.Operation` 枚举含 `QUERY`、`DELETE`                                                                                      | 同上                                                                                                  |
| `Document` 提供 `getId()` / `getText()` / `getMetadata()` / `getScore()`（`Double`，可能为 null）                                                           | `spring-ai-commons-2.0.0.jar`                                                                       |
| `VectorStore.similaritySearch(SearchRequest)`；`SearchRequest.builder().query(...).topK(...).similarityThreshold(...)`                               | `spring-ai-vector-store-2.0.0.jar`                                                                  |
| Jackson 3：`tools.jackson.databind.json.JsonMapper`；`tools.jackson.core.JacksonException extends RuntimeException`                                   | `jackson-databind-3.1.5.jar`、`jackson-core-3.1.5.jar`                                               |
| `@MockitoBean` 可用（`org.springframework.test.context.bean.override.mockito.MockitoBean`）                                                             | `spring-test-7.0.9.jar`                                                                             |
| 本工程元数据键：`file_name` / `doc_md5` / `chunk_index` / `chunk_total`                                                                                     | `SemanticTextSplitter.java`、`DocumentService.java`                                                  |

## 附录 B：手工验证命令

> 端口以实际启动为准（历史记录中出现过 `--server.port=8081`），下按 8081 示例。

1）确认 RAG 开关与前置链路（阶段 0）

```bash
curl -s -X POST http://localhost:8081/api/chat/multi-turn \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"宿舍晚上几点熄灯？\",\"ragEnabled\":true,\"topK\":4}"
```

期望：`ragUsed=true`，`references` 非空且 `score` 合理。

2）查看结构化日志

```bash
tail -n 1 logs/retrieval.jsonl | jq .
```

期望：字段同第 3.2 节，`outcome=HIT`，`hits` 长度等于 `hitCount`，`textPreview` 为 `null`。

3）验证无命中分支

```bash
curl -s -X POST http://localhost:8081/api/chat/multi-turn \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"zzzz完全不存在的主题qqqq\",\"ragEnabled\":true}"
tail -n 1 logs/retrieval.jsonl | jq '{outcome, hitCount, hits}'
```

期望：`outcome=EMPTY`、`hitCount=0`、`hits=[]`。

4）验证降级分支（停掉 Milvus 容器后执行）

```bash
docker compose stop standalone
curl -i -s -X POST http://localhost:8081/api/chat/multi-turn \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"宿舍晚上几点熄灯？\",\"ragEnabled\":true}"
tail -n 1 logs/retrieval.jsonl | jq '{outcome, degraded, errorMessage}'
docker compose start standalone
```

期望：接口仍返回 200（降级成功）；日志 `outcome=ERROR`、`degraded=true`。

5）验证开关生效

```bash
# 将 rag.retrieval.log.enabled 改为 false 并重启后：
wc -l logs/retrieval.jsonl   # 行数不再增长
```

6）验证内置兜底日志（仅排障时临时开启）

```bash
# application.properties: spring.ai.vectorstore.observations.log-query-response=true
# 重启后检索，日志中应出现 "Vector Store Query Response:" 字样
```

7）P1 查询接口

```bash
curl -s "http://localhost:8081/api/retrieval-logs?limit=5" | jq .
curl -s "http://localhost:8081/api/retrieval-logs?conversationId=<CID>&limit=20" | jq '.items | length'
```

8）文档入口：Knife4j `http://localhost:8081/doc.html`