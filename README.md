# Roch · 智能文档分析与可追溯报告生成系统

Roch 使用检索增强生成（RAG）处理用户选定的文档，支持知识库问答、逐条引用核验、多文档对比和模板化报告生成。报告中的事实可以展开查看来源文件、位置和原文摘录，结果可保存并导出为 PDF。

## 当前功能

### 文档与知识库

- 导入 TXT、DOCX、PDF、XLSX 和 XLS，按原始文件字节计算 MD5 去重。
- 保留标题、章节、代码块和表格结构，按语义切片；PDF 切片携带可用页码。
- 切片同步保存到 PostgreSQL 和 Milvus，提供向量、关键词和混合检索。混合检索使用 RRF 融合，默认检索模式为向量检索。
- 支持文档列表、详情、分页切片查询、原文件下载、删除和重新索引。已有原件时可直接重新索引。
- DOCX、表格或历史切片没有可靠页码时，展示可用章节或切片位置。扫描件的 OCR 和工作表单元格级定位尚未实现。

### 可追溯报告

- 提供总结（`SUMMARY`）、分析报告（`REPORT`）和多文档对比（`COMPARE`）。
- 报告事实与证据编号关联。后端回查切片所属文档，并核对存档原文摘录；来源匹配状态与模型判断的内容支持状态分别展示。
- 内容支持状态包括支持、部分支持、与原文冲突和证据不足。无引用的陈述会保留缺证标记。
- 报告保存生成时的引文快照，重新读取时检查当前原文件及切片状态，便于识别重建索引或删除后的变化。
- 多文档比较按“维度 × 文档”分别取证。每个有检索结果的单元格由模型按总要求和分章要求选择事实，然后核验引文与字段。单位可以确定性换算时保留原值和换算值。口径不一致、检索失败、提取失败和未找到依据分别说明。
- 分析报告和文档对比提供内置模板。用户可以选择章节、添加额外要求，并在独立的顺序区域排列已选章节。
- 创建任务分步填写，报告按章节阅读，事实和结论分页，对比结果按维度切换。导出配置和下载列表独立展示。
- PDF 内嵌中文字体，提供章节书签、顺序编号或文件位置引用，以及目录和证据附录开关。重新导出读取已保存结果，每次生成独立产物。
- 任务、结果登记、产物及配置持久化。进度通过 SSE 推送，失败可创建新任务重试。

### 问答与外部资料

- 支持单轮问答、多轮会话及多轮 SSE 流式输出，可按请求启用 RAG。
- 提供 JSON 抓取和网页正文提取。网页引擎支持 Jsoup 与 Firecrawl，Firecrawl 需要相应密钥。
- 多轮问答客户端注册了天气、地理编码和网页读取工具。天气及地理编码需要高德密钥，能否调用还取决于模型的工具调用能力。

自动判断需要人工复核。实际验收中，自由文本曾将等值预算描述为冲突，该句被标为“证据不足”。来源存在也不能保证结论正确，阅读时应同时核对摘录、适用条件和支持状态。

## 技术与依赖

下表的依赖版本来自本仓库 [后端 POM](rag/pom.xml)、[前端依赖](web/package.json)、[前端锁定文件](web/package-lock.json) 和 [Compose 文件](compose.yaml)。配置默认值来自 [应用配置](rag/src/main/resources/application.properties)。

| 用途 | 当前技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.1.1、Spring AI 2.0.0、Maven |
| 前端 | Vue 3、TypeScript、Vite 6、Lucide 图标 |
| 文档解析与 PDF | Apache POI 5.5.1、PDFBox 3.0.7、随应用打包的 Noto Sans SC 字体 |
| 持久化 | PostgreSQL 17、Spring Data JPA、Flyway |
| 检索 | Milvus v3.0.1、PostgreSQL 全文检索、Jieba 分词、RRF |
| 模型接口 | OpenAI 兼容协议；项目现有配置使用 LM Studio |
| 向量库依赖 | etcd、MinIO，由 Compose 一并启动 |
| 验证 | JUnit、Mockito、Testcontainers、前端 Node 测试与类型检查；可选质量评估使用 Python 与 pypdf |

部署机器需要 Java 21 或兼容的更高版本、可运行本项目的 Maven、Node.js/npm，以及 Docker Engine 或 Docker Desktop 和 Compose。项目现有验收记录使用 Maven 3.9.7；前端构建可使用 Node.js 22。仓库没有可用的 Maven Wrapper，以下命令使用系统安装的 `mvn`。

最低 RAM、GPU 显存和可支持并发数在项目材料中未提及，具体容量不确定，需要按选择的模型、文档规模和实际负载测量。

## 本地部署

以下部署路径采用“Docker 运行依赖，宿主机运行模型、后端与前端”。命令从项目根目录执行，前端命令另有说明。

### 获取代码与准备配置

```bash
git clone https://github.com/cwnk256/Roch.git
cd Roch
git switch main
java -version
mvn -version
node --version
docker compose version
```

在项目根目录创建 `.env`，填写自己的凭据。下列值是待替换示例，不能直接用作部署密码。

```dotenv
POSTGRES_PASSWORD='replace_with_your_database_password'
MINIO_ACCESS_KEY='replace_with_your_minio_user'
MINIO_SECRET_KEY='replace_with_your_minio_password'
DOCKER_VOLUME_DIRECTORY=.
```

Compose 会读取根目录 `.env`。Spring Boot 不会自动把这个文件加载成环境变量，启动后端时需要单独设置数据库密码等变量。`.env` 已由 `.gitignore` 排除。

如果使用已有数据，沿用原数据库密码、MinIO 凭据和卷目录。修改 `DOCKER_VOLUME_DIRECTORY` 会让容器读取另一个目录，可能表现为资料库为空。更改初始化用的 `POSTGRES_PASSWORD` 也不会自动修改已有 PostgreSQL 用户的密码。

### 启动 PostgreSQL 与向量库

```bash
docker compose config --quiet
docker compose up -d
docker compose ps
```

等待 `postgres`、`etcd`、`minio`、`standalone` 健康后再启动应用。容器名称和宿主机映射来自当前 Compose 文件：

| 服务 | 容器名 | 宿主机入口 |
| --- | --- | --- |
| PostgreSQL | `roch-postgres` | `localhost:5432`，数据库与用户均为 `roch` |
| Milvus | `milvus-standalone` | `localhost:19530` |
| Milvus HTTP/健康检查 | 同上 | `localhost:29091` |
| MinIO 对象接口 | `milvus-minio` | `localhost:29000` |
| MinIO 控制台 | 同上 | `localhost:29001` |

当前 Compose 文件保留了顶层 `version` 字段；本次配置检查通过，Compose 提示该字段已过时并忽略它。

检查依赖失败时使用 `docker compose logs --tail=100 postgres standalone etcd minio`。这些日志可能含环境或错误信息，分享前应检查敏感内容。

Compose 中 Milvus 镜像使用镜像代理地址 `docker.1ms.run/milvusdb/milvus:v3.0.1`。该代理在部署网络中是否可用不确定；拉取失败时，可将 `standalone.image` 改为部署网络可以访问的同版本镜像地址，并重新核对启动与健康状态。

### 可选服务 · 扫描 PDF OCR

从现有「导入文档」页面上传原始 PDF。带可用文字的普通 PDF 直接导入，扫描页与混合页中的扫描区域进入后台识别。任务展示排队、物理页识别进度、索引和失败状态，失败后可从同一任务重试。

在项目根目录构建并启动本机 PaddleOCR PP-StructureV3 服务。首次构建需要下载镜像、依赖和固定校验值的模型。启动后的识别使用镜像内预置模型。

```bash
docker compose -f compose.ocr.yaml config --quiet
docker compose -f compose.ocr.yaml up -d --build
curl http://127.0.0.1:18765/health
```

健康检查返回 `{"status":"ready"}` 后，在启动或重启后端前设置 `RAG_OCR_ENABLED=true`。服务通过本机回环地址接收渲染页。后续嵌入和报告生成配置本机模型服务时，识别出的文本也留在本机。端口冲突时设置 `ROCH_OCR_PORT`，并同步设置后端的 `RAG_OCR_BASE_URL`。常规文字 PDF 可在 OCR 关闭时导入。也可用本机 OCRmyPDF 制作带文字层的副本再上传，扫描表格的行列关系需单独核对。[OCRmyPDF 用法](https://ocrmypdf.readthedocs.io/en/stable/cookbook.html)

调用方可在 `POST /api/documents/pdf` 上加 `?ocr=auto` 启用检测。普通 PDF 响应沿用原格式。需要识别时返回 HTTP 202 和 `jobId`，使用 `GET /api/documents/pdf-imports/{jobId}` 查询，使用 `POST /api/documents/pdf-imports/{jobId}/retry` 重试失败任务。

OCR 引文保留原始 PDF 的物理页，表格引文可打开原页并显示单元格区域。扫描表格的结构化结论由 `full-v2` 核验，行列、单位或置信度不足时需查看原页复核。全局默认检索策略仍为 `baseline`。

备份原件目录时一并保留文档的 `ocr/` 存档和已有报告。失败或排队中的上传字节暂存在 PostgreSQL，任务成功后释放。日志记录识别耗时、失败页与待复核数量。原件、OCR 存档和引用快照适用同一文档数据保留要求。

### 准备模型服务

在 LM Studio 中准备生成模型与嵌入模型，启用 OpenAI 兼容服务。应用当前配置使用：

| 配置 | 当前值 |
| --- | --- |
| 模型服务地址 | `http://localhost:1234/v1` |
| 生成模型标识 | `google/gemma-4-e4b` |
| 嵌入模型标识 | `text-embedding-bge-m3` |
| 向量维度 | `1024` |

可通过模型接口核对实际标识：

```bash
curl http://localhost:1234/v1/models
```

接口返回的模型 ID 必须与应用配置一致。报告生成依赖结构化输出，需验证所选生成模型能返回要求的结构。导入和检索依赖嵌入模型，两类模型都应能正常调用。

更换远程模型服务时，设置服务地址、真实 API Key 和模型 ID。如果生成与嵌入模型来自不同服务，应分别配置 `spring.ai.openai.chat.base-url`、`spring.ai.openai.embedding.base-url` 及对应的 `api-key`，并验证两个接口。

更换嵌入模型时，即使维度相同，新旧向量通常也不可直接比较。创建独立集合、设置匹配的 `embeddingDimension` 并重新索引原始资料；不要继续向旧集合混写。

### 启动后端

以下两组命令按操作系统任选一组。绝对目录来自当前项目路径，避免 Maven、IDEA 与 JAR 的工作目录差异导致原件或报告无法找到。

Linux / macOS 的 Bash：

```bash
# 仅加载自己创建并检查过的 .env
set -a
. ./.env
set +a

export SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/roch'
export SPRING_DATASOURCE_USERNAME='roch'
export SPRING_AI_VECTORSTORE_MILVUS_COLLECTIONNAME='rag_docs'
export RAG_DOCUMENT_STORAGE_DIR="$PWD/data/documents"
export RAG_DOCUMENTTASK_OUTPUTDIR="$PWD/data/reports"
export SERVER_ADDRESS='127.0.0.1'

mvn -f rag/pom.xml clean package -DskipTests
java -jar rag/target/rag-0.0.1-SNAPSHOT.jar
```

Windows PowerShell：

```powershell
# 在项目根目录执行，密码需与 .env 中的 POSTGRES_PASSWORD 一致
$env:POSTGRES_PASSWORD = 'replace_with_your_database_password'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/roch'
$env:SPRING_DATASOURCE_USERNAME = 'roch'
$env:SPRING_AI_VECTORSTORE_MILVUS_COLLECTIONNAME = 'rag_docs'
$env:RAG_DOCUMENT_STORAGE_DIR = Join-Path (Get-Location).Path 'data/documents'
$env:RAG_DOCUMENTTASK_OUTPUTDIR = Join-Path (Get-Location).Path 'data/reports'
$env:SERVER_ADDRESS = '127.0.0.1'

mvn -f rag/pom.xml clean package -DskipTests
java -jar rag/target/rag-0.0.1-SNAPSHOT.jar
```

JAR 文件名来自当前 `rag/pom.xml`，修改版本后使用构建实际产出的文件名。Flyway 在启动时执行仓库中的数据库迁移，JPA 按表结构校验。运行账号需要创建/迁移表的权限，文件目录需要可读写。

开发时也可用 `mvn -f rag/pom.xml spring-boot:run`，或在 IDEA 中启动 `RagApplication`，并在运行配置中设置同样的环境变量。

### 启动前端

打开另一个终端：

```bash
cd web
npm ci
npm run dev
```

访问 `http://localhost:5173`。开发代理默认将 `/api` 转发到 `http://localhost:8080`。实际地址以 Vite 启动输出为准。

后端端口改变时，启动前设置 `VITE_API_PROXY_TARGET`：

```bash
# Bash
VITE_API_PROXY_TARGET=http://127.0.0.1:8089 npm run dev
```

```powershell
# PowerShell
$env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:8089'
npm run dev
```

### 完成部署验证

1. 请求 `GET http://localhost:8080/api/document-tasks/templates`，确认模板列表可读取。接口文档位于 `http://localhost:8080/doc.html`。
2. 在前端上传一份非扫描型 PDF 或 TXT，确认文档就绪、切片可查询；打开原件并核对内容。
3. 通过文件检索查询原文中的信息。问答需要检索时，在请求中启用 `ragEnabled`，或修改全局 RAG 开关。
4. 创建分析报告，展开一条事实，核对文件、位置、摘录和支持判断，再下载 PDF。
5. 选择两份不同资料创建对比任务，检查两份文档各自的依据、缺失项和比较口径。
6. 调整引用样式并重新导出，确认报告事实和证据关系一致。
7. 等任务完成后重启应用，验证历史结果和下载可用。启动恢复会将遗留的 `PENDING`、`RUNNING` 任务标为 `FAILED / TASK_INTERRUPTED`。

## 服务器发布

### 发布前端与反向代理

在 `web/` 执行 `npm ci && npm run build`，将 `web/dist/` 发布到静态 Web 服务。前端 API 使用相对路径 `/api`，生产静态文件需要同源反向代理。开发用的 `VITE_API_PROXY_TARGET` 不会替生产 Web 服务配置代理。

下面是 Nginx 配置示例，示例网站目录为 `/srv/roch/web/dist`，后端在本机 `8080` 运行；路径、监听端口、域名和超时按部署环境调整：

```nginx
server {
    listen 80;
    server_name your-domain.example;
    root /srv/roch/web/dist;
    index index.html;
    client_max_body_size 51m;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 600s;
    }
}
```

`proxy_pass` 保留 `/api` 前缀，`try_files` 使 `/reports` 等前端路由刷新后可访问。SSE 需要禁用代理缓冲；模型生成较慢时，还应协调代理超时、前端请求超时和模型服务超时。上述监听端口、网站目录及超时是配置示例，项目没有规定必须采用这些值。

将示例按实际路径与域名写入 Nginx 配置后，先运行 `nginx -t` 检查语法，再按部署环境的服务管理方式重新加载。正式发布时配置域名、证书和 HTTPS。

后端可交给 systemd 或部署环境的进程管理器运行，设置 Java 路径、启动目录、环境变量、运行账号及持久化目录权限。生产模型地址应指向后端可以访问的服务，`localhost` 始终表示后端所在机器或容器自身。

### 部署边界与运行保护

- 当前任务恢复和进度订阅按单实例运行设计。部署多个后端副本前，需要补充任务归属、租约或队列，以及跨实例的进度分发。
- 当前项目没有登录、租户隔离和文档访问权限体系。需要外部访问时，在网关配置身份认证、HTTPS 和访问控制，并限制网页抓取的可访问网络范围。
- Compose 将数据库、向量库和 MinIO 端口映射到宿主机。生产环境按网络需求限制映射地址和防火墙，不直接暴露数据服务到公网。
- 根目录 `Dockerfile` 仍是旧模板，引用了仓库缺失的 `mvnw` 与根目录 `pom.xml`，不能作为当前应用的一键构建步骤。当前 `docker compose up -d` 仅启动数据依赖。应用容器化需先调整构建上下文、JAR 路径、模型地址和数据挂载，再验证。
- 数据备份包含 PostgreSQL、向量库依赖卷、原文件和报告目录。JSON 结果中保存原文摘录，删除原件后报告快照仍可能包含这些内容，应按实际业务确定保留与访问规则。

## 需要按部署环境调整的配置

环境变量会覆盖配置文件。默认值或代码默认值见 [应用配置](rag/src/main/resources/application.properties)、[原件存储](rag/src/main/java/com/wuyunbin/rag/service/storage/LocalDocumentStorage.java)、[任务配置](rag/src/main/java/com/wuyunbin/rag/service/task/DocumentTaskProperties.java) 和 [检索配置](rag/src/main/java/com/wuyunbin/rag/retrieval/RetrievalProperties.java)。

| 配置项 | 当前默认或读取方式 | 何时调整 |
| --- | --- | --- |
| `spring.datasource.url / username / password` | `localhost:5432/roch`、`roch`、`POSTGRES_PASSWORD` | 数据库主机、库名、账号或密码变化 |
| `spring.ai.openai.base-url / api-key` | `http://localhost:1234/v1`、本地占位值 `lm-studio` | 模型主机、服务商或认证变化 |
| `spring.ai.openai.chat.model` | `google/gemma-4-e4b` | 选择生成模型，须与服务返回的 ID 一致 |
| `spring.ai.openai.embedding.model` | `text-embedding-bge-m3` | 选择嵌入模型，并规划集合与重建 |
| `spring.ai.vectorstore.milvus.client.host / port` | `localhost`、`19530` | Milvus 主机或端口变化 |
| `spring.ai.vectorstore.milvus.databaseName / collectionName` | `default`、`rag_docs` | 隔离环境或建立新索引 |
| `spring.ai.vectorstore.milvus.embeddingDimension` | `1024` | 与实际嵌入输出维度一致 |
| `spring.ai.vectorstore.milvus.initialize-schema` | `true` | 启动时创建集合，已有集合仍需兼容模型 |
| `server.address / server.port` | 本地部署示例限制 `127.0.0.1`，默认端口 `8080` | 对外监听、端口冲突或反向代理变化 |
| `rag.document.storage-dir` | `data/documents` | 原件及 OCR 存档，建议为部署实例指定绝对目录 |
| `rag.ocr.enabled / base-url` | `false / http://127.0.0.1:18765` | 按需开启扫描 PDF 导入，重启后生效 |
| `rag.ocr.render-dpi / page-timeout-seconds` | `200 / 180` | OCR 渲染分辨率与单页识别超时，重启后生效 |
| `rag.document-task.output-dir` | `data/reports` | 结果 JSON 与 PDF 的目录 |
| `rag.document-task.font-path` | `classpath:fonts/NotoSansSC-Regular.ttf` | 换字体时提供应用可读路径并验证中文导出 |
| `rag.document-task.max-context-chars` | `24000` | 协调模型上下文长度；它是字符预算，不是 token 数 |
| `rag.document-task.retrieval.strategy` | `baseline` | 文档任务的新建策略。重启后可选择 `hybrid-only`、`question-split`、`full` 或 `full-v2` 做隔离验收。已创建任务重试沿用保存的策略和预算 |
| `rag.retrieval.mode` | `vector` | 在 `vector / keyword / hybrid` 间切换并评测 |
| `rag.retrieval.rrf.k / vector-weight / keyword-weight` | `60 / 1.0 / 1.0` | 混合检索融合参数 |
| `rag.chat.rag.enabled / top-k / similarity-threshold` | `false / 8 / 0.35` | 问答是否默认检索、召回量与阈值 |
| `rag.chat.memory.max-messages` | `20` | 多轮上下文窗口 |
| `rag.chunking.pipeline-version / strategy` | `heading-aware-v2 / heading-aware-v1` | 策略变动后使用新版本重建 |
| `rag.chunking.similarity-threshold / min-chunk-size / max-chunk-size / embed-batch-size` | `0.35 / 50 / 1000 / 32` | 调整语义切片和嵌入批次，之后检查重建结果 |
| `rag.cleaning.enabled / lowercase / filter-toc` | 均为 `true` | 清洗、索引文本大小写和目录过滤 |
| `spring.servlet.multipart.max-file-size / max-request-size` | `50MB / 51MB` | 单个文件最多 50 MB，请求上限包含 multipart 表单开销。修改后重启后端，并同步代理的请求体上限 |
| `rag.page.engine / rag.page.firecrawl.api-key` | `auto`、`FIRECRAWL_API_KEY` 可为空 | 网页渲染与外部抓取需求 |
| `amap.geo.key / amap.weather.key` | `AMAP_GEO_KEY / AMAP_WEATHER_KEY`，默认空 | 启用高德工具 |
| `rag.retrieval.log.enabled / preview-chars` | `true / 0` | 检索日志和摘录预览，分享日志前检查敏感内容 |
| `rag.curl.storage-dir / rag.page.storage-dir` | `data/curl / data/pages_txt` | 外部抓取落盘位置 |

例如调整生成模型和 Milvus 主机，可在 Bash 启动前设置：

```bash
export SPRING_AI_OPENAI_CHAT_MODEL='your-loaded-model-id'
export SPRING_AI_VECTORSTORE_MILVUS_CLIENT_HOST='your-milvus-host'
```

填写服务 API Key 时通过运行环境或受控配置注入，避免把真实密钥写入版本库。模型名、文件路径和示例占位符需要替换成自己的值。

## 开发数据与业务数据切换

IDEA 的 Active profiles 可选择 `data-test` 或 `data-old`，也可用启动参数 `--spring.profiles.active=data-test`。两份文件都使用后端 `8080`：

| Profile | 数据库 | 集合 | 原件与报告目录 |
| --- | --- | --- | --- |
| `data-test` | `roch_trace_verify` | `roch_trace_verify` | `D:/idea/rag-xmut/logs/trace-verify/source` 与 `D:/idea/rag-xmut/logs/trace-verify/reports` |
| `data-old` | `roch` | `rag_docs` | `D:/idea/rag-xmut/data/documents` 与 `D:/idea/rag-xmut/data/reports` |

这两份配置记录了原开发机器的路径；新机器必须修改或覆盖路径。Compose 只初始化 `roch` 数据库，测试库需自行创建并导入测试资料。切换 Profile 后重启后端，检查是否还有数据库、集合和路径环境变量覆盖配置。后端地址变化时重启前端开发代理。

## 常用接口

启动应用后以 OpenAPI 文档为准，接口声明位于 [控制器目录](rag/src/main/java/com/wuyunbin/rag/controller)。

| 用途 | 方法与路径 |
| --- | --- |
| TXT / Word / PDF / Excel 导入 | `POST /api/documents`、`POST /api/documents/word`、`POST /api/documents/pdf`、`POST /api/documents/excel`，文件字段 `file` |
| 文档列表、详情和切片 | `GET /api/documents`、`GET /api/documents/{documentId}`、`GET /api/documents/{documentId}/chunks` |
| 原件下载 | `GET /api/documents/{documentId}/file` |
| 检索 | `POST /api/documents/search`、`/api/documents/search-keywords` |
| 删除与重建 | `DELETE /api/documents/{documentId}`、`POST /api/documents/{documentId}/reindex` |
| 模板列表、创建任务、历史 | `GET /api/document-tasks/templates`、`POST /api/document-tasks`、`GET /api/document-tasks` |
| 详情、进度 SSE、结构化结果 | `GET /api/document-tasks/{taskId}`，后缀 `/events`、`/result` |
| 产物列表、下载指定文件 | `GET /api/document-tasks/{taskId}/artifacts`、`GET /api/document-tasks/{taskId}/artifacts/{artifactId}` |
| 重试、重新导出、兼容 PDF 下载 | `POST /api/document-tasks/{taskId}/retry`、`POST /api/document-tasks/{taskId}/artifacts/pdf`、`GET /api/document-tasks/{taskId}/file` |
| 问答、多轮、流式多轮 | `POST /api/chat`、`/api/chat/multi-turn`、`/api/chat/multi-turn/stream` |
| 会话列表与创建 | `GET /api/chat/conversations`、`POST /api/chat/conversations` |
| 会话消息、清空与删除 | `GET /api/chat/conversations/{id}/messages`、`DELETE /api/chat/conversations/{id}`、`DELETE /api/chat/conversations/{id}/delete` |
| JSON 抓取、列表、网页提取 | `POST /api/curl`、`GET /api/curl/files`、`POST /api/curl/page` |
| 检索日志 | `GET /api/retrieval-logs`、`GET /api/retrieval-logs/{logId}` |

创建对比任务的请求示例，文档 ID 必须换成实际导入结果：

```json
{
  "type": "COMPARE",
  "documentIds": ["实际文档ID-A", "实际文档ID-B"],
  "instruction": "比较维护周期和预算，保留双方来源并说明口径。",
  "templateId": "COMPARISON",
  "templateVersion": "1",
  "sections": [
    {"sectionId": "matrix", "instruction": ""},
    {"sectionId": "differences", "instruction": "重点核对适用条件。"},
    {"sectionId": "conflicts", "instruction": ""}
  ],
  "comparisonDimensions": ["校准周期", "预算与支出"],
  "exportOptions": {
    "citationStyle": "NUMBERED",
    "includeToc": true,
    "includeEvidenceAppendix": true
  }
}
```

`sections` 数组就是生成顺序，必选章节必须保留。模板 ID、版本、章节标识应先从模板列表接口读取。比较预算由 [ComparisonLimits](rag/src/main/java/com/wuyunbin/rag/service/task/ComparisonLimits.java) 校验，当前最多选择 6 份文档、6 个维度，组合数最多为 24。

重新导出时，将 `exportOptions` 对象直接作为 `POST /api/document-tasks/{taskId}/artifacts/pdf` 的请求体。历史 v1 结果只有章节来源；新生成 v2 结果保存逐条事实、引文快照和冻结配置。

## 数据持久化、备份与升级

| 数据 | 位置或用途 |
| --- | --- |
| PostgreSQL 卷 | `volumes/postgres`，任务、配置、会话、切片及结果/产物登记 |
| 向量库依赖卷 | `volumes/etcd`、`volumes/minio`、`volumes/milvus` |
| 原件 | `rag.document.storage-dir` 指定目录，按文档 ID 保存 |
| 报告 | `rag.document-task.output-dir` 指定目录，包含任务结果 JSON 与 PDF |
| 检索日志 | 文件及内存缓冲；缓冲中的查询结果在进程重启后失效 |

上述卷路径相对于 `DOCKER_VOLUME_DIRECTORY`。原件和报告的默认相对路径则取决于应用工作目录，部署时应固定绝对路径。

备份或迁移前停止写入，记录代码、模型、配置及数据库版本。PostgreSQL 可用逻辑备份导出，例如：

```bash
docker exec roch-postgres pg_dump -U roch -d roch -Fc -f /tmp/roch.dump
docker cp roch-postgres:/tmp/roch.dump ./roch.dump
```

备份文件可能含原文与业务资料，请存到受控备份位置。向量库依赖目录应按一致的停写/停机窗口备份，原件和报告目录同时保存。恢复到隔离环境后，先检查记录数、原件、检索和 PDF 下载，再切换业务入口。

升级应用前备份数据库，保留 Flyway 迁移历史。迁移失败应按错误定位处理，直接删除数据库或迁移历史可能丢失资料和结构记录。修改解析或嵌入配置后，使用原件执行重新索引，并核对旧报告快照仍可读取。

PDF 切片现在会继承上页的章节层级。要修复旧 PDF 的跨页章节筛选，可用原件重新导入或在文档列表中点击「重新索引」。重建后检查章节筛选能找到跨页正文，并核对引用的页码。旧报告仍可按生成时保存的证据快照读取和重新导出。

## 测试与排查

后端测试中既有纯单元测试，也有 PostgreSQL Testcontainers 和真实 Milvus 集成测试。先运行 Docker 与 Compose 依赖，并给普通 Spring Boot 测试指定隔离数据库及向量集合；测试不会替你创建这个常规数据库。

例如先创建测试库，再在 Bash 中设置环境：

```bash
docker exec roch-postgres createdb -U roch roch_suite

export POSTGRES_PASSWORD='与数据库一致的实际密码'
export SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/roch_suite'
export SPRING_AI_VECTORSTORE_MILVUS_COLLECTIONNAME='roch_suite'
mvn -f rag/pom.xml test

cd web
npm ci
npm test
npm run build
```

若测试库已存在，省略 `createdb`。高德在线测试仅在提供相应密钥时启用；真实模型反例验收需显式设置 `ROCH_TRACE_MODEL_ACCEPTANCE=true`，并先按测试要求导入验收样本。这些跳过项应在 review 中单独记录。

- 启动报数据库认证失败时，核对后端环境变量与已有数据库用户密码；检查是否错误启用了本机开发 Profile。
- 嵌入维度报错时，核对模型实际输出、应用 `embeddingDimension` 与已存在集合的维度。
- 任务长时间生成或结构化输出解析失败时，检查模型是否加载、模型 ID、上下文预算和模型服务日志。
- 页面能打开但 `/api` 请求失败时，核对开发代理或生产反向代理的地址与前缀。
- 原件、历史结果或 PDF 不可用时，检查存储挂载、工作目录、权限及数据库登记的文件路径。
- Docker Desktop 启动失败时先看具体日志。之前本机出现过残留套接字阻塞启动的情况，不能据此假定所有相同提示都有同一原因，也不能由提示直接判断数据丢失。

## 仓库入口

- `rag/` — 后端 Maven 模块、迁移、提示词和测试。
- `web/` — 前端工作台与构建配置。
- `compose.yaml` — PostgreSQL 与向量库依赖。
- `scripts/trace_report_acceptance.py` — 隔离环境的上传、生成、重建和重导出验收。
- `scripts/trace_report_quality.py` — 按人工标注核对原文定位、事实覆盖和结构化比较结果。
- `scripts/trace_report_release_gate.py` — 使用冻结样本验证有据事实覆盖、引文定位、对比结论和延迟。
- 用 `run --variant full-v2` 分别记录修改前后的三轮结果，并在两次运行中使用相同的样本和模型。用 `compare-revisions --before-output <基线目录> --before-variant full-v2 --output <候选目录>` 比较结果。传入 `--latency-reference-output <原发布基线目录>` 可继续使用归档的 `full` 短文延迟作门槛。试卷复测在两次运行和比较命令中都传入 `--corpus exam-check=<冻结试卷样本目录>`。
- 扩样门槛按三轮中位数计算，分析报告至少通过 9/12 条，对比事实至少通过 10/13 条，对比结论类别和理由均正确至少 4/7 项。三轮合计引文定位率须至少 95%，所有支持事实关联的引文都须可独立定位。
- `rag/src/test/resources/traceability/` — 合成验收资料及标注。

脚本支持 `--help`。在独立 Python 环境中安装质量评估依赖后，可查看参数：

```bash
python -m pip install pypdf python-docx openpyxl
python scripts/trace_report_acceptance.py --help
python scripts/trace_report_quality.py --help
```

执行时记录任务 ID、模型与输入文件，避免把有限样本指标当成通用准确率承诺。
