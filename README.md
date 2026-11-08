# Roch - 基于 RAG 的文档检索/生成

## 项目简介

Roch 是一个基于 Spring AI 框架构建的检索增强生成（RAG）系统，支持多轮对话、文档智能导入、语义切片、检索日志记录以及 AI
工具调用等功能。项目采用模块化设计，可灵活扩展各类文档格式与向量数据库集成。

## 核心功能

### 文档导入与处理

- **多格式支持**：支持导入 TXT、Word（.docx）、PDF、Excel（.xlsx/.xls）格式文档
- **智能语义切片**：基于语义相似度的文本分块，保留文档结构（标题层级、代码块、表格等）
- **标题感知切片**：Markdown ATX 标题自动维护章节路径（`section_path`），形成语义硬边界，阻止跨章节合并
- **版本化管理**：支持同一文档不同切片策略版本的重建与去重，同版本重复导入自动跳过
- **MD5 查重机制**：按原始文件字节计算 MD5，自动跳过重复文件
- **表格自然语言增强**：Word/Excel 中的表格自动生成自然语言描述，提升检索命中率

### 智能问答

- **单轮对话**：无上下文记忆的简单问答
- **多轮对话**：基于会话（conversationId）的上下文记忆机制
- **流式输出**：支持 SSE 实时流式响应
- **知识库检索增强**：可配置的 RAG 开关、召回条数与相似度阈值
- **AI 工具调用**：集成高德地图 API，用户询问天气或地理位置时 AI 自动调用工具获取实时数据；多轮问答中用户给出网址时自动调用网页读取工具（Jsoup 直连 + Firecrawl 云端渲染 auto 回退）抓取正文并依据其回答

### 检索与日志

- **向量检索**：基于 Milvus 的语义相似度检索
- **检索日志**：记录每次检索的命中情况、延迟与元数据，支持内存缓冲查询
- **引用追溯**：返回文档片段摘要，便于答案溯源

### 文档检索系统（Phase 1）

- **文档列表与详情**：聚合 Milvus 切片元数据返回文档列表，支持按文件名关键词和类型过滤
- **文档切片查询**：按文档 ID 查询所有切片，支持章节关键词过滤与分页
- **文档级搜索**：自然语言查询 → 切片检索 → 文档聚合 → 文档级排序，支持 `documentIds` 过滤
- **文档删除**：按文档 ID 删除 Milvus 中所有切片（含所有版本）
- **文档重新索引**：重新上传原始文件触发切片重建，MD5 校验保证文件一致性
- **文档级聚合算法**：`documentScore = min(maxChunkScore + min(hitCount - 1, 4) * 0.02, 1.0)`，避免单文档切片数过多而无限叠加

### 智能文档生成与可靠处理（Phase 2 / Phase 3）

- **指定资料生成**：选择 1~N 个已上传文档 + 自然语言指令，只基于所选资料生成总结（SUMMARY）或报告（REPORT）
- **大纲先行、章节级 RAG**：先用 Spring AI 结构化输出生成报告大纲（标题/章节目的），再逐章节在所选文档范围内检索切片并生成正文，Evidence
  只来自真实检索结果
- **引用可追溯**：正文证据保留 documentId/chunkId/fileName/章节/页码（页码缺失不伪造），全文参考资料按 `documentId|chunkId`
  去重
- **PDF 导出**：PDFBox 3.x 输出封面、摘要、目录、章节正文、章节来源与参考资料，内嵌 Noto Sans SC 中文字体子集，自动分页换行，支持中文
  PDF 文本抽取
- **可靠任务生命周期**：任务异步执行并按 `PENDING/RUNNING/COMPLETED/FAILED` 流转；任务、选定文档、结构化结果登记与 Artifact
  元数据通过 PostgreSQL + Flyway 持久化，服务重启时会将未完成任务标记为失败以便重试
- **可追溯产物**：导入原文保存到本地；生成结果保存为 `result.json`，PDF 作为 Artifact 管理，支持查询任务历史、下载
  Artifact、FAILED 任务重试和从已有结果重新导出 PDF（不调用 LLM）

### 外部数据抓取

- **JSON API 抓取**：请求外部 JSON 接口并自动落盘，支持后续解析复用
- **网页转 Markdown**：将 HTML 页面转为 Markdown 文本，可直接导入 RAG 知识库

### 检索质量升级（Phase 4，进行中）

- **切片落库**：导入时切片同步写入 PostgreSQL `document_chunks` 表（含 Jieba 分词 tokens、章节路径、页码、pipeline_version），删除文档/旧版本清理同步删除
- **关键词检索**：PostgreSQL FTS（Java 侧 Jieba 分词 + `simple` 配置 + GIN 索引），`documentIds` 通过 SQL `doc_md5 IN (...)` 下推，独立端点 `/api/documents/search-keywords`
- **统一检索管道**：`RetrievalPipeline` 提供 `vector / keyword / hybrid` 三种模式；单路模式行为与历史版本完全一致，HYBRID 双路召回 + RRF 融合，任一路故障自动降级另一路
- **RRF 融合**：标准 Reciprocal Rank Fusion `score = Σ weight/(k+rank)`（k=60），按 chunkId 去重合并、补齐各路元数据；只依赖排名，规避向量分数与 ts_rank 量纲不一致问题
- **检索评测**：`scripts/retrieval_eval.py` 支持 `vector|keyword|hybrid` 三模式基线评测（Hit@K / Recall@K / MRR）；当前 hybrid 相比纯向量 Hit@1 0.8333→0.9167、MRR 0.9028→0.9583
- **默认保守**：Hybrid 默认关闭（`rag.retrieval.mode=vector`），可通过请求级 `mode` 参数按需启用；documentIds 约束贯穿全部模式

## 技术栈

| 类别    | 技术选型                                   |
|-------|----------------------------------------|
| 开发框架  | Spring Boot 4.1.1 + Spring AI 2.0.0    |
| 语言版本  | Java 21                                |
| 向量数据库 | Milvus 3.x                             |
| 关系数据库 | PostgreSQL 17（任务/Artifact/切片持久化，Flyway 迁移） |
| 关键词检索 | PostgreSQL FTS + Jieba 分词（jieba-analysis） |
| 文档解析  | Apache POI（Word/Excel）、PDFBox 3.x（PDF） |
| 网页解析  | Jsoup（HTML 转 Markdown）                 |
| 接口文档  | Knife4j + SpringDoc OpenAPI            |
| 构建工具  | Maven 3.9+                             |
| 容器化   | Docker / Docker Compose                |

## 快速开始

### Vue 3 工作台

前端位于 `web/`，使用 Vue 3 + Vite 构建，开发服务器会将 `/api` 请求代理到 `http://localhost:8080`：

```bash
cd web
npm install
npm run dev
```

工作台包含仪表盘、单轮/多轮问答、文件检索、文档导入、文档管理和总结/报告页面。生产构建使用：

```bash
npm run build
```

### 环境要求

- JDK 21+
- Maven 3.9+（项目未包含 Maven Wrapper，需本地安装或使用 IDEA 内置 Maven）
- Milvus 数据库（Docker 部署，参见 `compose.yaml`）
- LM Studio（本地大模型推理，提供 OpenAI 兼容 API）

### 本地运行

```bash
# 启动 Milvus（首次需要拉取镜像）
docker compose up -d

# 构建（需本地安装 Maven）
mvn clean package -DskipTests -f rag/pom.xml

# 启动 RAG 服务
mvn spring-boot:run -f rag/pom.xml
```

服务默认监听 `http://localhost:8080`，接口文档位于 `http://localhost:8080/doc.html`。

### Docker 部署

```bash
# 构建镜像
docker build -t roch:latest .

# 或使用 Docker Compose（含 Milvus）
docker compose up -d
```

## 配置说明

主要配置项位于 `rag/src/main/resources/application.properties`：

| 配置项                                           | 默认值                                    | 说明                                        |
|-----------------------------------------------|----------------------------------------|-------------------------------------------|
| `spring.ai.vectorstore.milvus.collectionName` | rag_docs                               | Milvus 集合名称                               |
| `spring.ai.vectorstore.milvus.databaseName`   | default                                | 数据库名称                                     |
| `rag.chunking.pipeline-version`               | heading-aware-v2                       | 切片流水线版本（变更触发重建）                           |
| `rag.chunking.strategy`                       | heading-aware-v1                       | 切片策略标识                                    |
| `rag.chunking.similarity-threshold`           | 0.35                                   | 语义切片相似度阈值                                 |
| `rag.chunking.min-chunk-size`                 | 50                                     | 最小切片字符数                                   |
| `rag.chunking.max-chunk-size`                 | 1000                                   | 最大切片字符数                                   |
| `rag.chat.memory.max-messages`                | 20                                     | 多轮对话记忆最大消息数                               |
| `rag.chat.rag.enabled`                        | false                                  | 是否默认启用 RAG 检索                             |
| `rag.chat.rag.top-k`                          | 8                                      | 检索召回条数                                    |
| `rag.chat.rag.similarity-threshold`           | 0.35                                    | 检索相似度阈值                                   |
| `rag.chat.tool.webpage.max-chars`             | 12000                                  | 多轮问答"读取网址"工具返回给模型的网页正文上限（超出截断） |
| `rag.retrieval.log.enabled`                   | true                                   | 是否记录检索日志                                  |
| `rag.retrieval.log.preview-chars`             | 0                                      | 检索日志正文预览字符数                               |
| `rag.retrieval.mode`                          | vector                                 | 默认检索模式（vector / keyword / hybrid）；hybrid 默认关闭 |
| `rag.retrieval.rrf.k`                         | 60                                     | RRF 常数 k（越大排名差异越平滑）                       |
| `rag.retrieval.rrf.vector-weight`             | 1.0                                    | RRF 向量路权重                                 |
| `rag.retrieval.rrf.keyword-weight`            | 1.0                                    | RRF 关键词路权重                                |
| `rag.curl.storage-dir`                        | data/curl                              | JSON 抓取落盘目录                               |
| `rag.page.storage-dir`                        | data/pages_txt                         | 网页转 Markdown 落盘目录                         |
| `rag.page.engine`                             | auto                                   | 网页抓取引擎：`jsoup`（本地直连）/ `firecrawl`（云端增强）/ `auto`（jsoup 为空或失败时回退 firecrawl） |
| `rag.page.firecrawl.api-key`                  | 空，取 `FIRECRAWL_API_KEY` 环境变量        | Firecrawl 云 API Key（`fc-` 开头）；留空时增强抓取返回明确配置提示 |
| `rag.page.firecrawl.base-url`                 | https://api.firecrawl.dev              | Firecrawl 服务地址（自托管实例可改）                     |
| `rag.page.firecrawl.timeout-ms`               | 30000                                  | Firecrawl 抓取超时（毫秒）                        |
| `rag.document-task.enabled`                   | true                                   | 是否启用文档任务（Phase 3）                         |
| `rag.document-task.persistence`               | jpa                                    | 任务持久化实现；可显式设为 `memory` 用于本地轻量调试           |
| `rag.document-task.output-dir`                | data/reports                           | 生成 PDF 的落盘目录（按 taskId 命名）                 |
| `rag.document-task.max-context-chars`         | 24000                                  | 单章节送入 LLM 的证据上下文最大字符数                     |
| `rag.document-task.font-path`                 | classpath:fonts/NotoSansSC-Regular.ttf | PDF 内嵌中文字体路径（支持 classpath: 与外部路径，缺失则任务失败） |
| `rag.document.storage-dir`                    | data/documents                         | 导入原始文件的本地存储目录                             |
| `amap.geo.key`                                | -                                      | 高德地理编码 API Key                            |
| `amap.weather.key`                            | -                                      | 高德天气查询 API Key                            |

> **注意**：完整配置项及默认值请参考 `application.properties` 源码。

## API 文档

启动后访问 `http://localhost:8080/doc.html` 查看 Knife4j 交互式文档。

### 聊天接口

#### 单轮聊天

```http
POST /api/chat
Content-Type: application/json

{
  "message": "你好，请介绍一下你自己。"
}
```

#### 多轮问答

```http
POST /api/chat/multi-turn
Content-Type: application/json

{
  "conversationId": "3f2a9c1e-8b44-4f0a-9d21-6c9b7a1f0e33",
  "message": "福州今天天气怎么样？",
  "ragEnabled": true,
  "topK": 8,
  "threshold": 0.5
}
```

> 当用户询问天气或地理位置时，AI 会自动调用高德地图工具（geocode → getWeather）获取实时数据并回答，无需额外配置。

#### 流式多轮问答

```http
POST /api/chat/multi-turn/stream
Accept: text/event-stream
Content-Type: application/json

{
  "conversationId": "3f2a9c1e-8b44-4f0a-9d21-6c9b7a1f0e33",
  "message": "详细说明一下"
}
```

#### 会话管理

| 方法     | 路径                                      | 说明     |
|--------|-----------------------------------------|--------|
| POST   | `/api/chat/conversations`               | 创建新会话  |
| GET    | `/api/chat/conversations/{id}/messages` | 查询会话历史 |
| DELETE | `/api/chat/conversations/{id}`          | 清空会话   |

### 文档导入接口

| 方法   | 路径                     | Content-Type                      | 说明          |
|------|------------------------|-----------------------------------|-------------|
| POST | `/api/documents`       | `multipart/form-data`（.txt）       | 导入 TXT 文档   |
| POST | `/api/documents/word`  | `multipart/form-data`（.docx）      | 导入 Word 文档  |
| POST | `/api/documents/pdf`   | `multipart/form-data`（.pdf）       | 导入 PDF 文档   |
| POST | `/api/documents/excel` | `multipart/form-data`（.xlsx/.xls） | 导入 Excel 文档 |

响应示例：

```json
{
  "chunkCount": 15,
  "md5": "e10adc3949ba59abbe56e057f20f883e",
  "duplicated": false,
  "message": "成功导入文档，切分为 15 个切片"
}
```

### 文档检索接口（Phase 1）

| 方法     | 路径                                    | 说明                                          |
|--------|---------------------------------------|---------------------------------------------|
| GET    | `/api/documents`                      | 文档列表（支持 `page`/`size`/`keyword`/`fileType`） |
| GET    | `/api/documents/{documentId}`         | 文档详情（`documentId` 即 `doc_md5`）              |
| GET    | `/api/documents/{documentId}/chunks`  | 文档切片查询（支持 `page`/`size`/`section`）          |
| POST   | `/api/documents/search`               | 文档搜索（自然语言 → 文档级排序；支持 `mode` 检索模式）           |
| POST   | `/api/documents/search-keywords`      | 关键词检索（PostgreSQL FTS + Jieba，按 ts_rank 排序）   |
| DELETE | `/api/documents/{documentId}`         | 删除文档（含所有版本切片，同步清理 `document_chunks`）        |
| POST   | `/api/documents/{documentId}/reindex` | 重新索引（multipart 上传文件可选，MD5 校验一致性）            |

#### 文档搜索请求示例

```http
POST /api/documents/search
Content-Type: application/json

{
  "query": "设备维护和安全要求",
  "topK": 10,
  "threshold": 0.5,
  "documentIds": ["e10adc3949ba59abbe56e057f20f883e"],
  "mode": "hybrid"
}
```

> `documentIds` 为空表示全库搜索；非空则只搜索指定文档，且在 vector/keyword/hybrid 三种模式下都贯穿到每一路召回（Milvus
> 标量过滤 / PostgreSQL `doc_md5 IN (...)` 下推，不在 Java 内存中后置过滤）。
> `mode` 可选 `vector`（默认，全局配置 `rag.retrieval.mode`）/ `keyword` / `hybrid`；hybrid 为双路召回 + RRF
> 融合，融合分为相对归一化分（top1=1.0），不可当绝对相似度阈值使用。

#### 文档搜索响应示例

```json
{
  "query": "设备维护和安全要求",
  "documents": [
    {
      "documentId": "e10adc3949ba59abbe56e057f20f883e",
      "fileName": "设备操作手册.pdf",
      "score": 0.97,
      "hitCount": 3,
      "matchedChunks": [
        {
          "chunkId": "a1b2...",
          "documentId": "e10adc3949ba59abbe56e057f20f883e",
          "fileName": "设备操作手册.pdf",
          "content": "每日开机前应检查……",
          "section": "设备维护 > 日常检查",
          "pageStart": null,
          "pageEnd": null,
          "chunkIndex": 21,
          "score": 0.93
        }
      ]
    }
  ]
}
```

> `pageStart`/`pageEnd`：PDF 切片已按页解析并将页码写入 `document_chunks`；关键词检索（search-keywords）返回真实页码，
> 向量检索切片 metadata 当前未携带页码，返回 null（不伪造）。聚合算法：
> `documentScore = min(maxChunkScore + min(hitCount - 1, 4) * 0.02, 1.0)`。
> 注意：hybrid 模式下 matchedChunks 的 score 为 RRF 归一化分（top1=1.0），非原始相似度。

### 文档任务接口（Phase 2 / Phase 3）

| 方法   | 路径                                                    | 说明                                      |
|------|-------------------------------------------------------|-----------------------------------------|
| POST | `/api/document-tasks`                                 | 创建异步文档任务，立即返回 PENDING（SUMMARY / REPORT） |
| GET  | `/api/document-tasks`                                 | 分页查询任务历史                                |
| GET  | `/api/document-tasks/{taskId}`                        | 查询任务状态                                  |
| GET  | `/api/document-tasks/{taskId}/result`                 | 查询结构化 GeneratedDocument 结果              |
| GET  | `/api/document-tasks/{taskId}/artifacts`              | 查询任务 Artifact                           |
| GET  | `/api/document-tasks/{taskId}/artifacts/{artifactId}` | 下载指定 Artifact                           |
| POST | `/api/document-tasks/{taskId}/retry`                  | 为 FAILED 任务创建新任务重试                      |
| POST | `/api/document-tasks/{taskId}/artifacts/pdf`          | 从 result.json 重新导出 PDF，不调用 LLM          |
| GET  | `/api/document-tasks/{taskId}/file`                   | 下载生成的 PDF                               |

#### 创建任务请求示例

```http
POST /api/document-tasks
Content-Type: application/json

{
  "type": "SUMMARY",
  "documentIds": [
    "e10adc3949ba59abbe56e057f20f883e",
    "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
  ],
  "instruction": "根据这些资料总结设备维护要求，重点说明维护周期和安全注意事项。"
}
```

> `documentIds` 必传且至少 1 个（为空返回 400）；执行前逐个校验文档存在且状态为 READY（不存在返回 404）。所有章节检索严格限定在所选文档内。

#### 创建任务响应示例

```json
{
  "taskId": "task-9f2b1c7a8d6e4f3ab0c1d2e3f4a5b6c7",
  "type": "SUMMARY",
  "status": "PENDING",
  "progress": 0,
  "currentStep": "PENDING"
}
```

> 任务创建后由后台线程执行，状态按 `PENDING -> RUNNING -> COMPLETED/FAILED` 更新，并持久化
`progress/currentStep/errorCode`。结果落盘于 `data/reports/{taskId}/result.json`，PDF 与 Artifact 位于任务目录下；任务未完成时下载返回
> 409。

### Phase 3 持久化集成测试

Phase 3 使用 PostgreSQL 17 + Spring Data JPA + Flyway 持久化任务、任务文档范围、结果登记和 Artifact 元数据。`compose.yaml`
提供 PostgreSQL 17 服务，测试使用 Testcontainers PostgreSQL 验证迁移和跨仓储读取：

```bash
mvn -f rag/pom.xml -Dtest=JpaDocumentTaskStoreIntegrationTest test
```

Docker Desktop 运行时，该测试会启动隔离的 PostgreSQL 17 容器；Milvus 仍按现有 Compose 配置运行于 `localhost:19530`。

### 检索日志查询接口

```http
GET /api/retrieval-logs?conversationId=xxx&limit=50
GET /api/retrieval-logs/{logId}
```

> **注意**：检索日志存储在内存缓冲区中，服务重启后数据会丢失。

### 工具接口

| 方法   | 路径                | 说明                        |
|------|-------------------|---------------------------|
| POST | `/api/curl`       | 请求外部 JSON API 并落盘         |
| GET  | `/api/curl/files` | 列出已保存的 JSON 文件            |
| POST | `/api/curl/page`  | 抓取网页转 Markdown（可直接导入 RAG） |

## 项目结构

```
roch/
├── Dockerfile                 # 多阶段构建镜像
├── compose.yaml               # Docker Compose 配置（Milvus + etcd + MinIO）
├── rag/                       # 主模块
│   ├── pom.xml                # Maven 依赖配置
│   ├── src/main/java/com/wuyunbin/rag/
│   │   ├── RagApplication.java           # 启动类
│   │   ├── cleaning/                     # 文档清洗与切片
│   │   │   ├── ChunkingProperties.java   # 切片参数外置配置
│   │   │   ├── ChunkIdFactory.java       # 稳定切片 ID（SHA-256）
│   │   │   ├── CleaningTextSplitter.java # 清洗 + 结构化分段入口
│   │   │   ├── StructuredSegmenter.java  # 结构化分段（含标题感知）
│   │   │   ├── SemanticTextSplitter.java # 语义切片
│   │   │   ├── MarkdownHeadingParser.java# ATX 标题解析
│   │   │   ├── SectionContext.java        # 章节上下文
│   │   │   └── TextSegment.java          # 分段数据模型
│   │   ├── config/                       # Spring 配置
│   │   │   ├── ChatConfig.java           # ChatClient、ChatMemory、工具注册
│   │   │   └── RestConfig.java           # RestTemplate、ObjectMapper
│   │   ├── controller/                   # REST 控制器
│   │   │   ├── ChatController.java       # 单轮聊天
│   │   │   ├── MultiTurnChatController.java  #多轮聊天
│   │   │   ├── DocumentController.java   # 文档导入（TXT/Word/PDF/Excel）
│   │   │   ├── DocumentTaskController.java # Phase 2：文档任务（创建/状态/下载 PDF）
│   │   │   ├── DemoController.java       # 工具接口（curl/page）
│   │   │   └── RetrievalLogController.java
│   │   ├── dto/                          # 数据传输对象
│   │   │   ├── ChatRequest.java
│   │   │   ├── MultiTurnChatRequest.java
│   │   │   ├── DocumentResponse.java
│   │   │   ├── DocumentMetadata.java     # Phase 1：文档元数据
│   │   │   ├── RetrievedChunk.java       # Phase 1：检索命中切片
│   │   │   ├── RetrievedDocument.java    # Phase 1：检索命中文档
│   │   │   ├── RetrievalOptions.java     # Phase 1：检索参数
│   │   │   ├── DocumentSearchRequest.java   # Phase 1：搜索请求
│   │   │   ├── DocumentSearchResponse.java  # Phase 1：搜索响应
│   │   │   ├── ChunkView.java            # Phase 1：切片视图
│   │   │   ├── PageResult.java           # Phase 1：通用分页响应
│   │   │   ├── DocumentTaskType.java     # Phase 2：SUMMARY / REPORT
│   │   │   ├── DocumentTaskStatus.java   # Phase 2：PENDING/RUNNING/COMPLETED/FAILED
│   │   │   ├── DocumentTask.java         # Phase 2：任务实体
│   │   │   ├── DocumentTaskRequest.java  # Phase 2：任务请求
│   │   │   ├── DocumentTaskResponse.java # Phase 2：任务响应（含 downloadUrl）
│   │   │   ├── GeoResponse.java          # 高德地理编码响应
│   │   │   ├── WeatherResponse.java      # 高德天气响应
│   │   │   └── ...
│   │   ├── retrieval/                    # 检索管道与日志
│   │   │   ├── RagRetriever.java
│   │   │   ├── ChunkRetriever.java       # Phase 1：切片检索抽象（向量）
│   │   │   ├── MilvusChunkRetriever.java # Phase 1：Milvus 实现（含 documentIds 过滤）
│   │   │   ├── KeywordRetriever.java     # Phase 4：关键词检索抽象（PostgreSQL FTS）
│   │   │   ├── PostgresKeywordRetriever.java # Phase 4：FTS 实现（Jieba 分词 + tsquery）
│   │   │   ├── RetrievalMode.java        # Phase 4：VECTOR / KEYWORD / HYBRID
│   │   │   ├── RetrievalPipeline.java    # Phase 4：统一切片检索入口（单路短路 / Hybrid+RRF / 降级）
│   │   │   ├── RrfFuser.java             # Phase 4：RRF 融合（k=60，去重合并，归一化）
│   │   │   ├── RetrievalProperties.java  # Phase 4：rag.retrieval.* 配置
│   │   │   ├── DocumentRetriever.java    # Phase 1：文档检索抽象
│   │   │   ├── DefaultDocumentRetriever.java  # Phase 1：管道 + 聚合实现
│   │   │   ├── RetrievalLogWriter.java
│   │   │   └── InMemoryRetrievalLogStore.java
│   │   ├── service/                      # 业务逻辑
│   │   │   ├── ChatService.java          # 单轮聊天
│   │   │   ├── MultiTurnChatService.java # 多轮聊天（含 RAG + 工具调用）
│   │   │   ├── DocumentService.java      # 文档导入（含版本化重建）+ Phase 1 文档管理 + Phase 4 切片落库/清理
│   │   │   ├── ChunkTokenizer.java       # Phase 4：Jieba 分词封装（存储/查询两种口径）
│   │   │   ├── DocumentSearchService.java   # Phase 1：文档搜索编排（Phase 4 支持 mode 参数）
│   │   │   ├── DocumentAggregationService.java  # Phase 1：切片聚合（Chunk → Document）
│   │   │   ├── WordDocumentParser.java   # Word 解析器
│   │   │   ├── PdfDocumentParser.java    # PDF 解析器
│   │   │   ├── ExcelDocumentParser.java  # Excel 解析器
│   │   │   ├── TableTextEnhancer.java    # 表格自然语言增强
│   │   │   ├── HtmlToMarkdownConverter.java # HTML 转 Markdown
│   │   │   ├── CurlService.java          # JSON API 抓取
│   │   │   ├── PageService.java          # 网页抓取转 Markdown
│   │   │   ├── task/                     # Phase 2：文档任务
│   │   │   │   ├── DocumentTaskService.java      # 任务生命周期
│   │   │   │   ├── DocumentTaskExecutor.java     # 生成编排（校验→检索→大纲→章节→组装）
│   │   │   │   ├── DocumentTaskValidator.java    # 请求校验
│   │   │   │   ├── DocumentTaskProperties.java   # rag.document-task.* 配置
│   │   │   │   ├── DocumentTaskStore.java        # 任务存储抽象
│   │   │   │   └── InMemoryDocumentTaskStore.java# 内存实现（ConcurrentHashMap）
│   │   │   ├── generation/               # Phase 2：结构化生成
│   │   │   │   ├── OutlineGenerator.java / LlmOutlineGenerator.java     # .entity(ReportPlan.class)
│   │   │   │   ├── SectionGenerator.java / LlmSectionGenerator.java     # 章节生成（真实 Evidence）
│   │   │   │   ├── DocumentSummaryGenerator.java / LlmDocumentSummaryGenerator.java
│   │   │   │   ├── GeneratedDocumentAssembler.java # 章节保序 + 引用去重
│   │   │   │   ├── PromptTemplates.java  # prompts/ 模板加载
│   │   │   │   └── model/                # ReportPlan/ReportSectionPlan/Evidence/GeneratedSection/GeneratedDocument
│   │   │   └── export/                   # Phase 2：文档导出
│   │   │       ├── DocumentExporter.java # 导出抽象（未来可扩 Markdown/Word）
│   │   │       ├── PdfExporter.java      # PDFBox 3.x 中文 PDF
│   │   │       └── ExportedFile.java
│   │   ├── tool/                         # AI 工具
│   │   │   ├── AmapTools.java            # 高德地图工具（天气/地理编码）
│   │   │   └── WebPageTools.java         # 网页读取工具（网址抓取正文，Jsoup + Firecrawl auto 回退）
│   │   └── splitter/                     # 文本分割器
│   │       └── SemanticTextSplitter.java
│   ├── src/main/resources/
│   │   ├── application.properties        # 主配置文件
│   │   ├── logback-spring.xml
│   │   ├── prompts/                      # Phase 2：文档任务 Prompt 模板
│   │   │   ├── document-task-outline.txt
│   │   │   ├── document-task-section.txt
│   │   │   └── document-task-summary.txt
│   │   └── fonts/
│   │       └── NotoSansSC-Regular.ttf    # Phase 2：内嵌中文字体子集（GB2312 覆盖）
│   └── src/test/java/                    # 单元测试
├── plan/                                 # 需求与设计文档
│   ├── multi-turn-chat/                  # 多轮对话实施计划
│   ├── recalled-document/                # 检索日志功能计划
│   └── semantic-chunking/                # 语义切片增强方案
└── scripts/
    ├── rag_eval.py                       # RAG 参数网格评测脚本
    ├── test_set.json                     # 评测测试集
    ├── rag_eval_results.csv             # 评测结果（gitignore）
    ├── retrieval_test_set.json          # Phase 4：检索评测测试集（14 题，含正负样本）
    ├── retrieval_eval.py                # Phase 4：检索基线评测（vector|keyword|hybrid）
    ├── retrieval_baseline.json          # Phase 4：纯向量基线结果
    ├── retrieval_baseline_hybrid.json   # Phase 4：Hybrid+RRF 基线结果
    ├── keyword_eval.py                  # Phase 4：关键词检索评测（search-keywords 端点）
    └── keyword_baseline.json            # Phase 4：关键词检索基线结果
```

## 开发指南

### 添加新文档格式支持

1. 在 `service/` 包下新建解析器类，将文档转为 Markdown 文本
2. 在 `DocumentService` 中注入并调用
3. 在 `DocumentController` 添加对应端点

### 扩展切片策略

1. 修改 `ChunkingProperties` 暴露新参数
2. 在 `CleaningTextSplitter` / `StructuredSegmenter` 中实现新策略
3. 提升 `rag.chunking.pipeline-version`，重新导入文档触发重建

### 添加新的 AI 工具

1. 在 `tool/` 包下新建组件类，方法用 `@Tool` 注解
2. 在 `ChatConfig.multiTurnChatClient` 中通过 `.defaultTools()` 注册

### 运行测试

```bash
# 运行所有测试
mvn test -f rag/pom.xml

# 运行特定测试类
mvn test -f rag/pom.xml -Dtest=ChunkingBaselineTest
```

## 演进路线

详见 `plan/` 目录下各阶段实施计划：

- **方案 01**：切片版本化与评测基线（已完成）
- **方案 02**：标题与章节感知切片（已完成）
- **方案 03**：自适应边界与窗口策略
- **方案 04**：父子分块与检索扩展
- **Phase 1**：文档检索能力（已完成）— 文档列表/详情/切片/搜索/删除/重新索引 + 切片检索抽象 + 文档级聚合排序
- **Phase 2**：智能文档生成（已完成）— 指定 1~N 个文档 + 自然语言指令，大纲驱动的章节级 RAG → 结构化 GeneratedDocument →
  内嵌中文字体的 PDF 下载（SUMMARY / REPORT）
- **Phase 3**：可靠文档处理（核心能力已完成）— 任务持久化、原文存储、异步执行、进度与状态、结果/Artifact
  管理、重试、重新导出与重启恢复；待完成真实依赖端到端验收和 PDF 页码元数据
- **Phase 4**：检索质量升级（进行中）— 检索评测基线（14 题，Hit@K/Recall@K/MRR）、切片落库 PostgreSQL
  `document_chunks`（Jieba 分词 + FTS）、KeywordRetriever、统一 RetrievalPipeline（vector/keyword/hybrid 三模式）、
  RRF 融合（k=60）；hybrid 相比纯向量 Hit@1 0.8333→0.9167、MRR 0.9028→0.9583，默认保持 vector 待扩测后评估。
  完成范围与待办见 `plan/文档处理/roch_phase4_revised_based_on_readme4_and_p3status-status.md`；
  待完成：Reranker、Query Rewrite、Context Selection、Parent-Child、COMPARE/EXTRACT/REVIEW、前端 Task Builder/Evidence UI

## 贡献者

感谢所有为项目提供反馈与贡献的开发者。如有建议或问题，欢迎通过 Gitee Issues 反馈。

### 文档原文与可靠性接口

导入的原始文件默认保存到 `data/documents/{documentId}`，可通过 `GET /api/documents/{documentId}/file` 读取。重新索引时
`file` 参数可选；省略时使用已保存原文，历史上未保存原文的文档会返回错误并要求重新上传。

生成任务会在结果持久化和 PDF 导出前校验证据范围，防止引用任务未选择的文档。Phase 3 的完成范围、待办和验证记录见
`plan/文档处理/roch_phase3_document_processing_reliability_implementation_plan-status.md`。
