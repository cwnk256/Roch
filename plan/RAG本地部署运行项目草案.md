# RAG 知识库系统本地部署与运行项目草案

> 文档版本：v2.0
> 编制日期：2026-09-14
> 项目代号：RAG-Local-Deploy

***

## 一、项目主题

### 1.1 项目名称

**基于 Spring AI 的 RAG 知识库系统本地部署与运行验证项目**

### 1.2 项目背景

当前 RAG 知识库系统（`rag-xmut`）已完成核心功能开发：

- TXT 文档语义切片导入（标题感知 v2 管线，PROSE/TABLE/CODE 三段式）
- 基于 Milvus + bge-m3 的向量检索
- Spring AI 多轮对话（会话记忆 + RAG 检索增强 + SSE 流式输出）
- 结构化检索日志（JSONL 格式，四路径埋点）

系统当前处于**开发完成、待部署验证**阶段。本项目目标是将系统在本地环境完整部署、运行起来，验证全链路（文档导入 → 向量检索 →
多轮问答）可用，为后续功能迭代（多格式文档支持、会话持久化、混合检索等）提供稳定可运行的基座。

### 1.3 项目目标

1. **基础设施部署**：通过 Docker Compose 启动 Milvus 全套组件（etcd + MinIO + Milvus standalone）
2. **LLM 环境就绪**：LM Studio 加载 gemma-4-e4b（对话）与 text-embedding-bge-m3（嵌入）模型
3. **应用构建运行**：Maven 构建 + Spring Boot 应用启动，端口 8080 对外服务
4. **全链路验证**：Word, PDF ,TXT 文档导入 → 向量检索 → 单轮/多轮问答 → SSE 流式输出，各环节功能正常
5. **冒烟测试通过**：核心接口（文档导入、单轮问答、多轮问答、会话管理）全部可调通

### 1.4 项目范围

**包含（In Scope）：**

- Docker Compose 编排 Milvus 基础设施
- LM Studio 模型加载与环境配置
- Maven 构建与 Spring Boot 应用启动
- application.properties 配置校验
- 全链路功能验证（导入 → 检索 → 问答）
- 核心接口冒烟测试
- 部署文档编写

**不包含（Out of Scope）：**

- OA 系统对接（SSO/ACL/文档同步等，留待后续阶段）
- 文档多格式扩展（Tika 集成，留待后续阶段）
- 会话记忆持久化（JdbcChatMemoryRepository，留待后续阶段）
- 混合检索 / Rerank（留待后续阶段）
- 生产环境部署（K8s/负载均衡/监控告警等）
- 前端页面开发

***

## 二、技术栈与环境要求

### 2.1 技术栈

| 层级     | 技术选型                  | 版本                 | 说明                                         |
|--------|-----------------------|--------------------|--------------------------------------------|
| 框架     | Spring Boot           | 4.1.1              | pom.xml parent                             |
| AI 框架  | Spring AI             | 2.0.0              | BOM 管理                                     |
| JDK    | OpenJDK               | 21（编译）/ 24（本机运行）   | `--release 21` 兼容                          |
| 构建工具   | Maven                 | 3.9.11             | 本机 wrapper                                 |
| 向量库    | Milvus                | v3.0.1             | Docker 镜像 `docker.1ms.run/milvusdb/milvus` |
| 对象存储   | MinIO                 | RELEASE.2025-09-07 | Milvus 依赖                                  |
| 元数据存储  | etcd                  | v3.5.18            | Milvus 依赖                                  |
| LLM 服务 | LM Studio             | 本地                 | OpenAI 兼容协议，端口 1234                        |
| 对话模型   | gemma-4-e4b           | -                  | LM Studio 加载                               |
| 嵌入模型   | text-embedding-bge-m3 | -                  | 1024 维，LM Studio 加载                        |
| API 文档 | Knife4j               | 5.6.0              | Spring Boot 4 兼容分支                         |

### 2.2 本机环境要求

| 项目        | 要求                                          | 说明                                                                 |
|-----------|---------------------------------------------|--------------------------------------------------------------------|
| 操作系统      | Windows 10/11 + WSL2                        | Docker Desktop 依赖 WSL2                                             |
| Docker    | Docker Desktop                              | 含 docker compose                                                   |
| JDK       | OpenJDK 24                                  | 路径 `C:\Users\29802\.jdks\openjdk-24.0.1`，`--release 21` 编译         |
| Maven     | 3.9.11                                      | 路径 `C:\Users\29802\.m2\wrapper\dists\apache-maven-3.9.11\...\bin\` |
| LM Studio | 已安装                                         | 需提前下载 gemma-4-e4b 与 bge-m3 模型                                      |
| 端口占用      | 8080 / 1234 / 19530 / 29000 / 29001 / 29091 | 均需可用                                                               |

### 2.3 端口规划

| 端口    | 服务             | 用途                          |
|-------|----------------|-----------------------------|
| 8080  | Spring Boot 应用 | RAG API 入口                  |
| 1234  | LM Studio      | OpenAI 兼容 API（对话 + 嵌入）      |
| 19530 | Milvus gRPC    | 向量库主端口                      |
| 29000 | MinIO API      | Milvus 对象存储（容器间 minio:9000） |
| 29001 | MinIO Console  | MinIO 管理界面                  |
| 29091 | Milvus WebUI   | Milvus 管理界面                 |

> **注意**：Windows Hyper-V/WSL2 会动态保留端口（如 8974-9073 含 9000/9001、9082-9181 含 9091），因此 compose.yaml 已将
> MinIO/Milvus WebUI 端口避开 9000/9001/9091，改为 29000/29001/29091。**切勿改回原端口**，否则会报 "access socket forbidden"。

***

## 三、部署与运行步骤

### 3.1 阶段划分

| 阶段      | 目标       | 里程碑                             |
|---------|----------|---------------------------------|
| Phase 1 | 基础设施部署   | M1：Milvus 健康，MinIO 可访问          |
| Phase 2 | LLM 环境就绪 | M2：LM Studio 两模型加载，API 可调       |
| Phase 3 | 应用构建与启动  | M3：Spring Boot 启动成功，Swagger 可访问 |
| Phase 4 | 全链路功能验证  | M4：导入 → 检索 → 问答全通               |

### Phase 1：基础设施部署（Milvus + MinIO + etcd）

| 步骤  | 操作                              | 验证                                                      |
|-----|---------------------------------|---------------------------------------------------------|
| 1.1 | 确认 Docker Desktop 已启动           | `docker info` 正常                                        |
| 1.2 | 在项目根目录执行 `docker compose up -d` | 拉取镜像并启动 3 个容器                                           |
| 1.3 | 检查容器状态                          | `docker compose ps` 三容器均 healthy                        |
| 1.4 | 访问 MinIO Console                | 浏览器打开 `http://localhost:29001`，账号 minioadmin/minioadmin |
| 1.5 | 访问 Milvus WebUI                 | 浏览器打开 `http://localhost:29091`，确认服务健康                   |
| 1.6 | 验证 Milvus gRPC 可达               | 应用启动后自动建 collection `rag_docs`                          |

**compose.yaml 关键配置（已就绪）：**

```yaml
services:
  etcd:        # 元数据存储
  minio:       # 对象存储（29000:9000, 29001:9001）
  standalone:  # Milvus（19530:19530, 29091:9091）
```

### Phase 2：LLM 环境就绪（LM Studio）

| 步骤  | 操作                             | 验证                                                         |
|-----|--------------------------------|------------------------------------------------------------|
| 2.1 | 启动 LM Studio                   | 进程运行                                                       |
| 2.2 | 加载对话模型 `google/gemma-4-e4b`    | 模型状态为 Loaded                                               |
| 2.3 | 加载嵌入模型 `text-embedding-bge-m3` | 模型状态为 Loaded                                               |
| 2.4 | 启动 Local Server（端口 1234）       | Server status: Running                                     |
| 2.5 | 验证对话 API                       | `curl http://localhost:1234/v1/chat/completions ...` 返回回复  |
| 2.6 | 验证嵌入 API                       | `curl http://localhost:1234/v1/embeddings ...` 返回 1024 维向量 |

### Phase 3：应用构建与启动

| 步骤  | 操作            | 验证                                                                    |
|-----|---------------|-----------------------------------------------------------------------|
| 3.1 | 设置 JAVA\_HOME | `C:\Users\29802\.jdks\openjdk-24.0.1`                                 |
| 3.2 | 执行 Maven 构建   | `mvn clean package -DskipTests`（或 `mvn clean package` 跑全量测试）          |
| 3.3 | 确认 8080 端口空闲  | 无残留 Java 进程占用                                                         |
| 3.4 | 启动应用          | `java -jar rag/target/rag-0.0.1-SNAPSHOT.jar` 或 `mvn spring-boot:run` |
| 3.5 | 检查启动日志        | 无异常，Milvus collection `rag_docs` 自动创建                                 |
| 3.6 | 访问 Swagger    | 浏览器打开 `http://localhost:8080/doc.html`（Knife4j）                       |

**关键配置（application.properties 已就绪）：**

```properties
# LLM
spring.ai.openai.base-url=http://localhost:1234/v1
spring.ai.openai.chat.options.model=google/gemma-4-e4b
spring.ai.openai.embedding.options.model=text-embedding-bge-m3
# Milvus
spring.ai.vectorstore.milvus.client.host=localhost
spring.ai.vectorstore.milvus.client.port=19530
spring.ai.vectorstore.milvus.collectionName=rag_docs
spring.ai.vectorstore.milvus.embeddingDimension=1024
spring.ai.vectorstore.milvus.initialize-schema=true
# RAG
rag.chat.rag.enabled=false        # 默认关闭，请求级可开启
rag.chat.rag.top-k=8
rag.chat.rag.similarity-threshold=0.5
```

### Phase 4：全链路功能验证

| 步骤   | 操作             | 验证                                                                                        |
|------|----------------|-------------------------------------------------------------------------------------------|
| 4.1  | 准备测试 TXT 文档    | 任意 UTF-8 编码 .txt 文件                                                                       |
| 4.2  | 调用文档导入接口       | `POST /api/documents`（multipart/form-data），返回 chunks 数量与 MD5                              |
| 4.3  | 调用单轮问答（不开 RAG） | `POST /api/chat`，返回模型回复                                                                   |
| 4.4  | 新建会话           | `POST /api/chat/conversations`，返回 conversationId                                          |
| 4.5  | 调用多轮问答（开 RAG）  | `POST /api/chat/multi-turn`，body 含 `ragEnabled=true` + `conversationId`，返回回复 + references |
| 4.6  | 验证上下文记忆        | 追问「那它的价格是多少？」，模型能理解「它」指代上文                                                                |
| 4.7  | 调用 SSE 流式问答    | `POST /api/chat/multi-turn/stream`，逐块收到 conversation/rag/data 事件                          |
| 4.8  | 查询会话历史         | `GET /api/chat/conversations/{id}/messages`，返回历史消息列表                                      |
| 4.9  | 清空会话           | `DELETE /api/chat/conversations/{id}`，返回 cleared=true                                     |
| 4.10 | 检查检索日志         | 查看 JSONL 日志文件，确认 SKIPPED/HIT/EMPTY/ERROR 四路径埋点正常                                          |

***

## 四、小组分工

### 4.1 角色与职责

| 角色        | 人数 | 职责                                  |
|-----------|----|-------------------------------------|
| 项目负责人     | 1  | 整体统筹、进度把控、环境资源协调                    |
| 基础设施工程师   | 1  | Docker Compose 编排、Milvus 部署、端口/网络排障 |
| LLM 环境工程师 | 1  | LM Studio 模型下载加载、API 验证、模型替换测试      |
| 应用开发工程师   | 1  | Maven 构建、Spring Boot 启动排障、配置校验      |
| 测试工程师     | 1  | 冒烟测试用例编写与执行、全链路验证、缺陷记录              |

### 4.2 任务分解（WBS）

#### Phase 1：基础设施部署

| 任务编号   | 任务                                     | 负责角色 |
|--------|----------------------------------------|------|
| P1-T01 | 确认 Docker Desktop 安装与 WSL2 就绪          | 基础设施 |
| P1-T02 | 执行 `docker compose up -d` 启动 Milvus 全套 | 基础设施 |
| P1-T03 | 验证三容器 healthy 状态                       | 基础设施 |
| P1-T04 | 验证 MinIO Console 可访问（29001）            | 基础设施 |
| P1-T05 | 验证 Milvus WebUI 可访问（29091）             | 基础设施 |

#### Phase 2：LLM 环境就绪

| 任务编号   | 任务                                     | 负责角色   |
|--------|----------------------------------------|--------|
| P2-T01 | 下载 gemma-4-e4b 模型至 LM Studio           | LLM 环境 |
| P2-T02 | 下载 text-embedding-bge-m3 模型至 LM Studio | LLM 环境 |
| P2-T03 | 启动 LM Studio Local Server（端口 1234）     | LLM 环境 |
| P2-T04 | 验证对话 API（/v1/chat/completions）         | LLM 环境 |
| P2-T05 | 验证嵌入 API（/v1/embeddings），确认 1024 维     | LLM 环境 |

#### Phase 3：应用构建与启动

| 任务编号   | 任务                                    | 负责角色 |
|--------|---------------------------------------|------|
| P3-T01 | 配置 JAVA\_HOME 与 Maven 环境              | 应用开发 |
| P3-T02 | 执行 `mvn clean package -DskipTests` 构建 | 应用开发 |
| P3-T03 | 确认 8080 端口无残留进程占用                     | 应用开发 |
| P3-T04 | 启动 Spring Boot 应用                     | 应用开发 |
| P3-T05 | 检查启动日志，确认 Milvus collection 自动创建      | 应用开发 |
| P3-T06 | 验证 Knife4j Swagger 页面可访问              | 应用开发 |

#### Phase 4：全链路功能验证

| 任务编号   | 任务                 | 负责角色 |
|--------|--------------------|------|
| P4-T01 | 编写冒烟测试用例文档         | 测试   |
| P4-T02 | 验证 TXT 文档导入接口      | 测试   |
| P4-T03 | 验证单轮问答接口           | 测试   |
| P4-T04 | 验证多轮问答接口（含 RAG 检索） | 测试   |
| P4-T05 | 验证 SSE 流式问答接口      | 测试   |
| P4-T06 | 验证会话管理（新建/查询/清空）   | 测试   |
| P4-T07 | 验证检索日志（JSONL 四路径）  | 测试   |
| P4-T08 | 汇总测试结果，出具验证报告      | 测试   |

### 4.3 推荐小组构成（示例）

| 成员   | 角色           | 专注领域                            |
|------|--------------|---------------------------------|
| 成员 A | 项目负责人 + 应用开发 | 统筹、Maven 构建、Spring Boot 启动、配置校验 |
| 成员 B | 基础设施工程师      | Docker Compose、Milvus 部署、端口排障   |
| 成员 C | LLM 环境工程师    | LM Studio 模型加载、API 验证           |
| 成员 D | 测试工程师        | 冒烟测试、全链路验证、缺陷记录                 |

> 最小可行团队可为 2 人：1 人兼项目负责人 + 应用开发，1 人兼基础设施 + LLM 环境 + 测试。

***

## 五、风险与应对

| 风险                                   | 影响               | 应对                                           |
|--------------------------------------|------------------|----------------------------------------------|
| 8080 端口被残留 Java 进程占用                 | 应用启动失败           | 启动前 `netstat -ano \| findstr :8080` 查杀残留进程   |
| Windows Hyper-V 保留端口冲突               | Docker 绑定端口失败    | 已避开 9000/9001/9091，使用 29000/29001/29091；切勿改回 |
| LM Studio 模型下载慢/失败                   | Phase 2 阻塞       | 提前下载模型文件；备选使用 Ollama 作为 OpenAI 兼容替代          |
| Milvus 首次启动镜像拉取慢                     | Phase 1 阻塞       | 使用国内镜像源 `docker.1ms.run`（compose.yaml 已配置）   |
| JDK 版本不匹配                            | 构建失败             | 本机 JDK 24 + `--release 21` 编译；无 JDK 21 也可兼容  |
| 并行多个 mvn 构建                          | surefire fork 挂死 | 同一项目禁止并行构建                                   |
| ChunkingProperties 直接实例化死循环          | CPU 空转           | 字段已带初始化默认值，勿直接 new                           |
| Milvus `Document.getScore()` 返回 null | 排序异常             | 代码已有 null 降级处理                               |

***

## 六、验收标准

| 验收项     | 标准                                                       |
|---------|----------------------------------------------------------|
| 基础设施    | Milvus + MinIO + etcd 三容器 healthy                        |
| LLM 服务  | LM Studio 两模型 Loaded，对话/嵌入 API 可调                        |
| 应用启动    | Spring Boot 无异常启动，端口 8080 监听                             |
| 文档导入    | TXT 文件导入成功，返回 chunks 数量与 MD5                             |
| 单轮问答    | `/api/chat` 返回模型回复                                       |
| 多轮问答    | `/api/chat/multi-turn`（ragEnabled=true）返回回复 + references |
| 上下文记忆   | 追问能理解前文指代                                                |
| SSE 流式  | `/api/chat/multi-turn/stream` 逐块输出                       |
| 会话管理    | 新建/查询历史/清空均正常                                            |
| Swagger | `http://localhost:8080/doc.html` 可访问                     |
| 检索日志    | JSONL 日志四路径埋点正常                                          |
| 降级韧性    | Milvus 故障时对话不中断（降级普通问答）                                  |

***

## 七、交付物清单

1. 本项目草案文档
2. 部署运行手册（含环境配置、启动命令、验证步骤）
3. 冒烟测试用例文档
4. 全链路验证报告（含截图/日志）
5. 已知问题与后续迭代建议

***

## 八、后续迭代方向（本期不实施）

本地部署验证通过后，按优先级逐步推进：

| 优先级 | 方向      | 说明                                       |
|-----|---------|------------------------------------------|
| P0  | 文档多格式支持 | Apache Tika 集成，支持 .docx/.xlsx/.pdf/.pptx |
| P1  | 会话记忆持久化 | JdbcChatMemoryRepository 替换进程内存储         |
| P1  | 混合检索    | BM25 + 向量，提升精确词查询召回                      |
| P2  | Rerank  | bge-reranker-v2-m3 二次排序                  |
| P2  | 鉴权接入    | Spring Security + JWT（为未来 OA 对接预留）       |
| P3  | OA 系统对接 | SSO + ACL + 文档自动同步                       |

***

## 九、附录

### 9.1 现有系统接口清单

| 接口                                      | 方法     | 用途                            |
|-----------------------------------------|--------|-------------------------------|
| `/api/documents`                        | POST   | TXT 文档导入（multipart/form-data） |
| `/api/chat`                             | POST   | 单轮问答（无记忆）                     |
| `/api/chat/multi-turn`                  | POST   | 多轮问答（含 RAG + 记忆）              |
| `/api/chat/multi-turn/stream`           | POST   | SSE 流式多轮问答                    |
| `/api/chat/conversations`               | POST   | 新建会话                          |
| `/api/chat/conversations/{id}/messages` | GET    | 查询会话历史                        |
| `/api/chat/conversations/{id}`          | DELETE | 清空会话                          |

### 9.2 Docker Compose 服务清单

| 服务         | 镜像                                    | 端口映射                    | 用途          |
|------------|---------------------------------------|-------------------------|-------------|
| etcd       | quay.io/coreos/etcd:v3.5.18           | 内部 2379                 | Milvus 元数据  |
| minio      | minio/minio:RELEASE.2025-09-07        | 29000:9000, 29001:9001  | Milvus 对象存储 |
| standalone | docker.1ms.run/milvusdb/milvus:v3.0.1 | 19530:19530, 29091:9091 | Milvus 向量库  |

### 9.3 关键配置项（application.properties 现状）

```properties
# LLM（LM Studio）
spring.ai.openai.base-url=http://localhost:1234/v1
spring.ai.openai.chat.options.model=google/gemma-4-e4b
spring.ai.openai.embedding.options.model=text-embedding-bge-m3
# Milvus
spring.ai.vectorstore.milvus.client.host=localhost
spring.ai.vectorstore.milvus.client.port=19530
spring.ai.vectorstore.milvus.collectionName=rag_docs
spring.ai.vectorstore.milvus.embeddingDimension=1024
spring.ai.vectorstore.milvus.initialize-schema=true
# RAG 检索
rag.chat.rag.enabled=false
rag.chat.rag.top-k=8
rag.chat.rag.similarity-threshold=0.5
# 切片管线
rag.chunking.pipeline-version=heading-aware-v2
rag.chunking.strategy=heading-aware-v1
rag.chunking.similarity-threshold=0.35
rag.chunking.max-chunk-size=1000
rag.chunking.embed-batch-size=32
# 会话记忆
rag.chat.memory.max-messages=20
# 检索日志
rag.retrieval.log.enabled=true
rag.retrieval.log.preview-chars=0
```

### 9.4 参考文件位置

| 文件             | 路径                                                                           |
|----------------|------------------------------------------------------------------------------|
| Docker Compose | `compose.yaml`                                                               |
| Dockerfile     | `Dockerfile`                                                                 |
| Maven POM      | `rag/pom.xml`                                                                |
| 应用配置           | `rag/src/main/resources/application.properties`                              |
| 文档导入服务         | `rag/src/main/java/com/wuyunbin/rag/service/DocumentService.java`            |
| 多轮对话服务         | `rag/src/main/java/com/wuyunbin/rag/service/MultiTurnChatService.java`       |
| 检索服务           | `rag/src/main/java/com/wuyunbin/rag/retrieval/RagRetriever.java`             |
| Spring AI 配置   | `rag/src/main/java/com/wuyunbin/rag/config/ChatConfig.java`                  |
| 文档导入接口         | `rag/src/main/java/com/wuyunbin/rag/controller/DocumentController.java`      |
| 多轮问答接口         | `rag/src/main/java/com/wuyunbin/rag/controller/MultiTurnChatController.java` |

### 9.5 冒烟测试脚本（示例）

```bash
# 1. 导入 TXT 文档
curl -X POST http://localhost:8080/api/documents \
  -F "file=@test.txt"

# 2. 单轮问答
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'

# 3. 新建会话
curl -X POST http://localhost:8080/api/chat/conversations

# 4. 多轮问答（开 RAG）
curl -X POST http://localhost:8080/api/chat/multi-turn \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"<上一步返回的ID>","message":"文档讲了什么？","ragEnabled":true}'

# 5. 查询会话历史
curl http://localhost:8080/api/chat/conversations/<ID>/messages

# 6. 清空会话
curl -X DELETE http://localhost:8080/api/chat/conversations/<ID>
```

