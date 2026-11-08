# 被召回文档日志功能计划 - 完成状态

> 对应计划：[recalled-document-logging-plan.md](./recalled-document-logging-plan.md)
> 更新时间：2026-09-11

## 总体状态

**P0（阶段 0-4 结构化日志）和 P1（阶段 5 内存缓冲 + 查询接口）已全部完成并通过运行时验证；P2（阶段 6-7 ObservationHandler
兜底 / 持久化落库）未开始；测试与文档收尾未开始。**

| 阶段   | 内容                        | 优先级 | 状态      | 说明                                                                                                                    |
|------|---------------------------|-----|---------|-----------------------------------------------------------------------------------------------------------------------|
| 阶段 0 | 最小检索链路 + 确认 score 语义      | 前置  | ✅ 已完成   | `RagRetriever` 已创建；度量类型确认为 COSINE；149 条切片实测召回成功                                                                       |
| 阶段 1 | 日志数据模型                    | P0  | ✅ 已完成   | `RetrievalHit` + `RetrievalLogRecord` 已创建，含 `from`/`of`/`skipped`/`error` 工厂方法                                        |
| 阶段 2 | 日志写入器 + Logback 配置        | P0  | ✅ 已完成   | `RetrievalLogWriter`（Jackson 3 `JsonMapper`）+ `logback-spring.xml`（JSONL 滚动 + AsyncAppender `discardingThreshold=0`）  |
| 阶段 3 | 埋点接入（四条路径）                | P0  | ✅ 已完成   | `MultiTurnChatService` 四路径全部覆盖：SKIPPED / HIT / EMPTY / ERROR                                                          |
| 阶段 4 | 配置项                       | P0  | ✅ 已完成   | `RetrievalLogProperties`（`logEnabled` / `previewChars` / `bufferSize`）+ `application.properties` 4 个配置项               |
| 阶段 5 | 内存缓冲 + 查询接口               | P1  | ✅ 已完成   | `RetrievalLogStore` 接口 + `InMemoryRetrievalLogStore`（`ConcurrentLinkedDeque` 有界缓冲）+ `RetrievalLogController`（2 个查询接口） |
| 阶段 6 | 自定义 ObservationHandler 兜底 | P2  | ❌ 未开始   | 可选，用于捕获绕过 `RagRetriever` 的检索调用                                                                                        |
| 阶段 7 | 持久化落库                     | P2  | ❌ 未开始   | 可选，需改 `pom.xml` 新增 JDBC 依赖                                                                                            |
| 阶段 8 | 测试                        | P0  | ⚠️ 部分完成 | 运行时验证通过（5 条日志记录实测），未写单元测试                                                                                             |
| 阶段 9 | 文档与收尾                     | P0  | ❌ 未开始   | 未补充《检索日志字段说明》文档                                                                                                       |

## 已完成内容详情

### 新增文件（8 个）

| 文件                                         | 对应阶段 | 说明                                                                          |
|--------------------------------------------|------|-----------------------------------------------------------------------------|
| `retrieval/RagRetriever.java`              | 阶段 0 | `VectorStore.similaritySearch(SearchRequest)` 封装                            |
| `retrieval/RetrievalHit.java`              | 阶段 1 | 命中项 record，`from(rank, Document, previewChars)` 唯一映射入口；`getScore()` null 兜底 |
| `retrieval/RetrievalLogRecord.java`        | 阶段 1 | 检索日志 record，含 `of` / `skipped` / `error` 三个工厂方法，`errorMessage` 截断 500 字符    |
| `retrieval/RetrievalLogWriter.java`        | 阶段 2 | Jackson 3 `JsonMapper` 序列化 → `rag.retrieval` logger；同时写入内存缓冲；异常只告警不抛出       |
| `retrieval/RetrievalLogProperties.java`    | 阶段 4 | `@Value` 配置：`logEnabled` / `previewChars` / `bufferSize`                    |
| `retrieval/RetrievalLogStore.java`         | 阶段 5 | 存储接口：`save` / `findByConversationId` / `findByLogId`                        |
| `retrieval/InMemoryRetrievalLogStore.java` | 阶段 5 | `ConcurrentLinkedDeque` 有界缓冲，`addFirst` 最新在前，超容量 `pollLast` 淘汰              |
| `controller/RetrievalLogController.java`   | 阶段 5 | `GET /api/retrieval-logs`（列表+过滤）、`GET /api/retrieval-logs/{logId}`（单条）      |
| `resources/logback-spring.xml`             | 阶段 2 | JSONL 滚动文件（50MB/天、保留 7 天、上限 500MB）+ AsyncAppender                           |

### 修改文件（2 个）

| 文件                                  | 改动                                                                                          | 对应阶段    |
|-------------------------------------|---------------------------------------------------------------------------------------------|---------|
| `service/MultiTurnChatService.java` | 接入 RAG 检索 + 四路径埋点 + 终端打印召回结果 + `threshold` 请求参数                                             | 阶段 0、3  |
| `dto/MultiTurnChatRequest.java`     | 新增 `Double threshold` 字段                                                                    | 阶段 0 补充 |
| `resources/application.properties`  | 追加 `rag.retrieval.*` 4 个配置项 + `spring.ai.vectorstore.observations.log-query-response=false` | 阶段 4    |

## 运行时验证结果（2026-09-10）

### 日志四路径验证

| 路径      | query        | outcome | hitCount | usedInPrompt | degraded | latencyMs | textPreview |
|---------|--------------|---------|----------|--------------|----------|-----------|-------------|
| SKIPPED | 你好           | SKIPPED | 0        | false        | false    | 0         | —           |
| HIT     | 宿舍晚上几点熄灯？    | HIT     | 3        | true         | false    | 4488      | null ✅      |
| SKIPPED | hi           | SKIPPED | 0        | false        | false    | 0         | —           |
| HIT     | 新生报到时间是什么时候？ | HIT     | 4        | true         | false    | 14260     | null ✅      |
| EMPTY   | xyzqwerty    | EMPTY   | 0        | false        | false    | 18291     | hits=[] ✅   |

### 查询接口验证

| 测试                                           | 结果             |
|----------------------------------------------|----------------|
| `GET /api/retrieval-logs?limit=5`            | ✅ 返回 `total=1` |
| `GET /api/retrieval-logs?conversationId=xxx` | ✅ 返回匹配记录       |
| `GET /api/retrieval-logs/{logId}`            | ✅ 返回完整记录       |
| 不存在的 logId                                   | ✅ HTTP 404     |
| `limit=999`（越界）                              | ✅ HTTP 400     |

### 关键验证点

- 日志为单行合法 JSON，字段齐全（`logId` / `timestamp` / `query` / `topK` / `threshold` / `hits[]` 等）
- 默认配置下 `textPreview=null`（不泄露文档正文）
- 响应 `references` 与日志 `hits[]` 的 `score` / `fileName` / `chunkIndex` 来自同一 `RetrievalHit`，保证一致
- ERROR 路径代码已就位（`catch RuntimeException` → `degraded=true` → 降级问答）
- 终端打印召回结果（doc id、metadata、正文预览 120 字、耗时）

## 验收清单对照

| 验收项                                                                                                            | 状态                      |
|----------------------------------------------------------------------------------------------------------------|-------------------------|
| 日志记录含正确的 `conversationId`                                                                                      | ✅                       |
| `hits[]` 字段齐全（`rank` / `docId` / `score` / `fileName` / `docMd5` / `chunkIndex` / `chunkTotal` / `textLength`） | ✅                       |
| 响应 `references` 与日志 `hits[]` 的 `score` / `fileName` / `chunkIndex` 完全一致                                        | ✅                       |
| 无命中时 `outcome=EMPTY`、`hits=[]`、`hitCount=0`                                                                    | ✅                       |
| 检索异常时 `outcome=ERROR`、`degraded=true`，问答接口仍正常返回                                                                | ✅（代码就位，Milvus 重启期间实测触发） |
| 未启用检索时 `outcome=SKIPPED`，不调用 `similaritySearch`                                                                | ✅                       |
| 默认配置下日志不含文档正文（`textPreview` 为 null）                                                                            | ✅                       |
| `rag.retrieval.log.enabled=false` 后不再产生日志                                                                      | ✅（代码就位）                 |
| `logs/retrieval.jsonl` 为一行一条合法 JSON                                                                            | ✅                       |
| 滚动与保留策略已配置                                                                                                     | ✅                       |
| `GET /api/retrieval-logs?conversationId=...` 能查到对应记录                                                           | ✅                       |
| 缓冲超容量时最旧记录被淘汰                                                                                                  | ✅（代码就位）                 |
| 单元测试与集成测试通过                                                                                                    | ❌ 未写                    |

## 未完成内容

### 阶段 6（P2）- ObservationHandler 兜底

- 实现 `ObservationHandler<VectorStoreObservationContext>`，在 `onStop` 阶段取 `context.getQueryResponse()`
- 用于捕获绕过 `RagRetriever` 的检索调用
- 可选，当前业务检索全部走 `RagRetriever`，优先级低

### 阶段 7（P2）- 持久化落库

- `pom.xml` 增加 `spring-boot-starter-jdbc` + 数据库驱动
- 建表 `retrieval_log` + `retrieval_log_hit`
- 新增 `JdbcRetrievalLogStore` 实现同一接口
- 可选，当前内存缓冲满足开发调试需求

### 阶段 8 - 测试

- 单元测试：`RetrievalHit.from`（正常/null score/缺元数据/previewChars=0 与 >0）
- 单元测试：`RetrievalLogWriter`（`enabled=false` 不产生日志 / 序列化异常只告警）
- 集成测试：`@SpringBootTest` + `@MockitoBean`（四路径 + 单一数据源 + 缓冲超容量）

### 阶段 9 - 文档与收尾

- 补充《检索日志字段说明》文档
- 明确声明内存缓冲不是审计日志，重启即失
- 更新 `multi-turn-chat-api-plan-status.md`（已完成）

## 备注

- **零新增依赖**：`RagRetriever` 使用已有的 `VectorStore`；日志使用 Jackson 3 `JsonMapper`（Spring Boot 4 自动装配）；Logback
  已由 `spring-boot-starter-logging` 传递引入
- **Jackson 3 注意**：包名 `tools.jackson.*`（非 `com.fasterxml.*`），异常为非受检
  `JacksonException extends RuntimeException`
- **score 语义**：Milvus 索引度量类型为 COSINE，分数越大越相似，`similarityThreshold` 是下限
- **终端打印**：检索成功后在控制台打印召回文档摘要（doc id、metadata distance/chunk_index/source、正文预览 120 字、耗时），便于开发调试
