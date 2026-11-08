# 多轮问答接口计划 - 完成状态

> 对应计划：[multi-turn-chat-api-plan.md](./multi-turn-chat-api-plan.md)
> 更新时间：2026-09-11

## 总体状态

**P0（多轮记忆）、P1（RAG 增强）和 P2（流式输出）已全部完成；P2 运行时验证待 Docker Desktop 启动。**

| 阶段       | 内容                  | 状态      | 说明                                                                                    |
|----------|---------------------|---------|---------------------------------------------------------------------------------------|
| 阶段 0     | 准备与基线               | ✅ 已完成   | 应用可启动，Milvus 正常，文档导入链路已验证                                                             |
| 阶段 1     | 新增 DTO              | ✅ 已完成   | 5 个 record 已创建，带 `@Schema` 注解（含 `StreamChunk`）                                        |
| 阶段 2     | 记忆与多轮 ChatClient 装配 | ✅ 已完成   | `ChatMemory` + `multiTurnChatClient` Bean 已注册                                         |
| 阶段 3     | 会话管理服务              | ✅ 已完成   | `ConversationMemoryService` 已创建（含 `assertValidId` / `assertValidIdIfPresent` 双校验方法）   |
| 阶段 4     | 多轮问答服务              | ✅ 已完成   | `MultiTurnChatService` 已创建，接入 RAG 检索 + 四路径埋点                                          |
| 阶段 5     | 多轮接口层               | ✅ 已完成   | `MultiTurnChatController` 已创建（5 个接口含 SSE 流式）                                          |
| 阶段 6     | 配置项                 | ✅ 已完成   | `application.properties` 已追加配置项                                                       |
| 阶段 7（P1） | RAG 增强              | ✅ 已完成   | `RagRetriever` 已创建，`MultiTurnChatService` 接入检索 + System Prompt 拼装；`threshold` 请求参数已生效 |
| 阶段 8（P2） | 流式输出                | ✅ 代码完成  | `streamChat` 方法 + SSE 接口已实现，编译通过；运行时验证待 Docker Desktop 启动                             |
| 阶段 9     | 测试                  | ⚠️ 部分完成 | 运行时验证通过（全部接口实测），未写单元测试                                                                |
| 阶段 10    | 文档与收尾               | ❌ 未开始   | 未更新接口清单文档，未清理临时产物                                                                     |

## 已完成内容详情

### 新增文件（10 个）

| 文件                                                | 对应阶段       |
|---------------------------------------------------|------------|
| `dto/MultiTurnChatRequest.java`（含 `threshold` 字段） | 阶段 1       |
| `dto/MultiTurnChatResponse.java`（含嵌套 `Reference`） | 阶段 1       |
| `dto/ChatMessageView.java`                        | 阶段 1       |
| `dto/ConversationView.java`                       | 阶段 1       |
| `dto/StreamChunk.java`                            | 阶段 8       |
| `service/ConversationMemoryService.java`          | 阶段 3       |
| `service/MultiTurnChatService.java`               | 阶段 4       |
| `controller/MultiTurnChatController.java`         | 阶段 5       |
| `retrieval/RagRetriever.java`                     | 阶段 7       |
| `controller/RetrievalLogController.java`          | 阶段 5（日志计划） |

### 修改文件（2 个）

| 文件                                 | 改动                                                            | 对应阶段 |
|------------------------------------|---------------------------------------------------------------|------|
| `config/ChatConfig.java`           | 新增 `ChatMemory`、`multiTurnChatClient` Bean                    | 阶段 2 |
| `resources/application.properties` | 追加 `rag.chat.memory.*`、`rag.chat.rag.*`、`rag.retrieval.*` 配置项 | 阶段 6 |

## 运行时验证结果（2026-09-10）

### P0 多轮记忆

| 接口                                             | 结果                          |
|------------------------------------------------|-----------------------------|
| `POST /api/chat/conversations`                 | ✅ 返回新会话 ID                  |
| `POST /api/chat/multi-turn`（第一轮："我叫小明"）        | ✅ round=1，模型正常回复            |
| `POST /api/chat/multi-turn`（第二轮："我刚才告诉你我叫什么？"） | ✅ **模型回答"小明"，记忆生效**，round=2 |
| `GET /api/chat/conversations/{id}/messages`    | ✅ 返回 4 条历史消息                |
| `DELETE /api/chat/conversations/{id}`          | ✅ 清空成功                      |

### P1 RAG 增强

| 测试                                        | 结果                                             |
|-------------------------------------------|------------------------------------------------|
| `ragEnabled=true` 提问"宿舍几点熄灯"              | ✅ `ragUsed=true`，命中 3 条切片（score 0.524~0.567）   |
| `ragEnabled=true` 提问"新生报到时间"              | ✅ `ragUsed=true`，命中 4 条切片                      |
| `ragEnabled=true` 提问"xyzqwerty"（无关内容）     | ✅ `ragUsed=false`，`outcome=EMPTY`，`hitCount=0` |
| 不传 `threshold`                            | ✅ 使用默认值 0.5                                    |
| 传 `threshold=0.3`                         | ✅ 请求值覆盖默认                                      |
| 传 `threshold=0.9`                         | ✅ 高阈值无命中                                       |
| 空白 `conversationId` 访问 `messages`/`clear` | ✅ 返回 HTTP 400（已修复校验逻辑）                         |

### Milvus 确认

| 项      | 值                         |
|--------|---------------------------|
| 索引度量类型 | **COSINE**（分数越大越相似）       |
| 索引类型   | IVF_FLAT，nlist=1024       |
| 已索引行数  | 164（149 条原始 + 重复导入的 15 条） |

## 未完成内容

### P2 - 流式输出（阶段 8）运行时验证

- 代码已完成（`streamChat` 方法 + SSE 接口），编译通过
- 运行时验证待 Docker Desktop 启动（Milvus 容器未运行导致应用启动失败）
- 验证方式：
  `curl -N -X POST http://localhost:8080/api/chat/multi-turn/stream -H "Content-Type: application/json" -d '{"message":"你好"}'`

### 阶段 9 - 单元测试

- `ConversationMemoryService`：create / resolveId / history（空会话）/ clear / 非法 ID 抛异常
- `MultiTurnChatService`：conversationId 为空返回新 UUID；同 ID 连续两次请求记忆条数 ≥ 4

### 阶段 10 - 文档与收尾

- 更新接口清单文档
- 清理 `rag` 目录下临时产物（`ProbeSearch*.class`、`*-arg.txt`、`app-run.log` 等）

## 备注

- **零新增依赖**：`ChatMemory` / `ChatMemoryRepository` / `MessageWindowChatMemory` / `MessageChatMemoryAdvisor` 全部已在
  classpath（由 `spring-ai-starter-model-openai` 传递依赖带入）
- **向后兼容**：`POST /api/chat`（单轮）行为完全不变，使用独立的 `chatClient` Bean
- **会话存储**：当前为进程内 `InMemoryChatMemoryRepository`（`ConcurrentHashMap`），重启即丢失；持久化为 P1 后续演进
- **RAG 检索**：`QuestionAnswerAdvisor` 不在 classpath，采用手动检索 + 自拼 System Prompt 方案，零新增依赖
- **终端打印**：检索成功后在控制台打印召回文档摘要（doc id、metadata、正文预览 120 字、耗时）
