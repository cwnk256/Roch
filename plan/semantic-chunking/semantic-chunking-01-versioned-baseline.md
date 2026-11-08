# 语义切片版本化与评测基线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让切片参数、策略版本、稳定切片 ID 和重建行为可配置、可测试、可回滚。

**Architecture:** 保留当前 `CleaningTextSplitter -> SemanticTextSplitter` 算法，只增加 `ChunkingProperties`
和统一的切片元数据。导入时先写入新策略版本，再删除同一 `doc_md5` 的旧策略版本；同策略版本重复导入仍直接跳过。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Spring AI 2.0.0、Milvus、JUnit 5、Mockito。

## Global Constraints

- 不改变现有问答接口路径和响应 DTO 字段。
- 默认配置必须保持当前行为：相似度 `0.7`、最小块 `50`、最大块 `1000`。
- 单元测试不得访问 LM Studio、Milvus 或网络。
- 新元数据统一使用 snake_case：`chunk_pipeline_version`、`chunk_strategy`、`chunk_id`。
- 每个任务完成后独立验证，不把多个未验证改动混在一起。
- 当前仓库没有 Maven Wrapper；执行任务前必须准备 IDEA 测试运行器或安装 Maven 3.9+。

---

### Task 1: 外置切片参数

**Files:**

- Create: `rag/src/main/java/com/wuyunbin/rag/cleaning/ChunkingProperties.java`
- Modify: `rag/src/main/java/com/wuyunbin/rag/splitter/SemanticTextSplitter.java:25-37`
- Modify: `rag/src/main/java/com/wuyunbin/rag/config/ChatConfig.java:49-55`
- Modify: `rag/src/main/resources/application.properties:48-54`
- Test: `rag/src/test/java/com/wuyunbin/rag/splitter/SemanticTextSplitterTest.java`

**Interfaces:**

- Consumes: Spring `EmbeddingModel`、`ChunkingProperties`。
- Produces: `SemanticTextSplitter(EmbeddingModel, ChunkingProperties)`；后续任务通过 `properties.getPipelineVersion()` 和
  `properties.getStrategy()` 读取策略信息。

- [x]**Step 1: 写配置行为失败测试**

```java
@Test
void usesConfiguredChunkLimits() {
    ChunkingProperties properties = new ChunkingProperties();
    properties.setSimilarityThreshold(0.7);
    properties.setMinChunkSize(50);
    properties.setMaxChunkSize(1000);
    properties.setEmbedBatchSize(32);

    SemanticTextSplitter splitter = new SemanticTextSplitter(fakeEmbeddingModel(), properties);

    assertEquals(50, splitter.getMinChunkSize());
    assertEquals(1000, splitter.getMaxChunkSize());
}
```

- [x]**Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=SemanticTextSplitterTest#usesConfiguredChunkLimits test`

Expected: 编译失败，提示 `ChunkingProperties` 不存在或构造器参数不匹配。

- [x]**Step 3: 新增配置对象**

```java
@Getter
@Setter
@Component
public class ChunkingProperties {

    @Value(${rag.chunking.pipeline-version:legacy-v1})
    private String pipelineVersion;

    @Value(${rag.chunking.strategy:legacy-greedy})
    private String strategy;

    @Value(${rag.chunking.similarity-threshold:0.7})
    private double similarityThreshold;

    @Value(${rag.chunking.min-chunk-size:50})
    private int minChunkSize;

    @Value(${rag.chunking.max-chunk-size:1000})
    private int maxChunkSize;

    @Value(${rag.chunking.embed-batch-size:32})
    private int embedBatchSize;
}
```

- [x]**Step 4: 改造构造器和配置 Bean**

`SemanticTextSplitter` 保存 `ChunkingProperties`，由构造器读取四个旧参数；为测试保留只读 getter。
`ChatConfig.semanticTextSplitter` 改为：

```java
@Bean
public SemanticTextSplitter semanticTextSplitter(EmbeddingModel embeddingModel,
                                                  ChunkingProperties properties) {
    return new SemanticTextSplitter(embeddingModel, properties);
}
```

- [x]**Step 5: 增加默认配置**

```properties
# ============ 切片策略与参数 ============
rag.chunking.pipeline-version=legacy-v1
rag.chunking.strategy=legacy-greedy
rag.chunking.similarity-threshold=0.7
rag.chunking.min-chunk-size=50
rag.chunking.max-chunk-size=1000
rag.chunking.embed-batch-size=32
```

- [x]**Step 6: 运行测试并提交**

Run: `mvn -q -f rag/pom.xml -Dtest=SemanticTextSplitterTest test`

Expected: PASS。

```bash
git add rag/src/main/java/com/wuyunbin/rag/cleaning/ChunkingProperties.java rag/src/main/java/com/wuyunbin/rag/splitter/SemanticTextSplitter.java rag/src/main/java/com/wuyunbin/rag/config/ChatConfig.java rag/src/main/resources/application.properties rag/src/test/java/com/wuyunbin/rag/splitter/SemanticTextSplitterTest.java
git commit -m 'feat: externalize semantic chunking configuration'
```

### Task 2: 写入稳定切片元数据

**Files:**

- Create: `rag/src/main/java/com/wuyunbin/rag/cleaning/ChunkIdFactory.java`
- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java:76-89`
- Modify: `rag/src/main/java/com/wuyunbin/rag/splitter/SemanticTextSplitter.java:49-59`
- Test: `rag/src/test/java/com/wuyunbin/rag/cleaning/ChunkIdFactoryTest.java`

**Interfaces:**

- Consumes: `docMd5`、`pipelineVersion`、`blockType`、`chunkIndex`、切片正文。
- Produces: `String create(String docMd5, String pipelineVersion, String blockType, int chunkIndex, String content)`，返回
  64 位小写 SHA-256 十六进制字符串。

测试输入使用 `md5`、`v2`、`prose` 和两段不同正文。

```java
String first = ChunkIdFactory.create(md5, version, blockType, 0, content);
String second = ChunkIdFactory.create(md5, version, blockType, 0, content);
assertEquals(first, second);
assertEquals(64, first.length());
```

- [x]**Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=ChunkIdFactoryTest test`

Expected: FAIL，`ChunkIdFactory` 不存在。

- [x]**Step 3: 实现 SHA-256 ID**

`ChunkIdFactory.create` 将 MD5、策略版本、块类型、切片序号和正文用 `|` 连接，以 UTF-8 计算 SHA-256，并返回
`HexFormat.of().formatHex(...)` 的小写十六进制结果。`NoSuchAlgorithmException` 转为 `IllegalStateException`。

- [x]**Step 4: 在最终切片文档中补元数据**

只在 `CleaningTextSplitter` 生成最终 `Document` 的循环中写入，保证表格和代码也一致：

```java
String chunkId = ChunkIdFactory.create(docMd5, pipelineVersion,
        draft.blockType(), i, draft.content());
metadata.put(chunk_id, chunkId);
metadata.put(chunk_pipeline_version, pipelineVersion);
metadata.put(chunk_strategy, strategy);
```

- [x]**Step 5: 验证元数据和 ID 唯一性**

对同一文档生成的切片断言：`chunk_id` 数量等于切片数且无重复，`chunk_pipeline_version` 等于配置值，`chunk_strategy` 等于配置值。

Run: `mvn -q -f rag/pom.xml -Dtest=ChunkIdFactoryTest,CleaningTextSplitterTest test`

Expected: PASS。

- [x]**Step 6: 提交**

```bash
git add rag/src/main/java/com/wuyunbin/rag/cleaning/ChunkIdFactory.java rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java rag/src/main/java/com/wuyunbin/rag/splitter/SemanticTextSplitter.java rag/src/test/java/com/wuyunbin/rag/cleaning/ChunkIdFactoryTest.java rag/src/test/java/com/wuyunbin/rag/cleaning/CleaningTextSplitterTest.java
git commit -m 'feat: add stable chunk identity metadata'
```

### Task 3: 支持同文件策略版本重建

**Files:**

- Modify: `rag/src/main/java/com/wuyunbin/rag/service/DocumentService.java:52-73`
- Modify: `rag/src/main/java/com/wuyunbin/rag/service/DocumentService.java:76-91`
- Test: `rag/src/test/java/com/wuyunbin/rag/service/DocumentServiceTest.java`

**Interfaces:**

- Consumes: `doc_md5`、`chunk_pipeline_version`。
- Produces: `ImportDecision resolveImportDecision(String md5, String version)`，取值为 `SKIP_CURRENT`、
  `REBUILD_NEW_VERSION`、`NEW_DOCUMENT`。

- [x]**Step 1: 写导入决策失败测试**

同 MD5 且同版本返回 `SKIP_CURRENT`；同 MD5 但版本不同返回 `REBUILD_NEW_VERSION`；无任何命中返回 `NEW_DOCUMENT`。

- [x]**Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=DocumentServiceTest test`

Expected: FAIL，`ImportDecision` 或决策方法不存在。

- [x]**Step 3: 将标量查重升级为版本查询**

做两次存在性查询，不依赖返回 metadata：第一次表达式同时匹配 `doc_md5` 和当前 `chunk_pipeline_version`，命中则跳过；第二次只匹配
`doc_md5`，命中则重建；两次都未命中则按新文档导入。

```text
metadata[doc_md5] == md5 and metadata[chunk_pipeline_version] == version
metadata[doc_md5] == md5
```

- [x]**Step 4: 先写新版本，再清理旧版本**

`REBUILD_NEW_VERSION` 分支按以下顺序执行：

1. 生成新切片并执行 `vectorStore.add(chunks)`。
2. 新版本写入成功后，使用 Milvus `DeleteParam` 删除同一 `doc_md5` 且版本不等于当前版本的旧切片。
3. 删除失败只记录 `log.error`，不能把已成功写入的新版本误报为导入失败。
4. 删除表达式使用 `metadata[doc_md5] == md5 and metadata[chunk_pipeline_version] != currentVersion`。

- [x]**Step 5: 验证三条导入路径**

Run: `mvn -q -f rag/pom.xml -Dtest=DocumentServiceTest test`

Expected: 同版本跳过、新版本重建、新文件导入三个测试全部 PASS，且 `vectorStore.add` 只在新版本或新文件分支调用。

- [x]**Step 6: 提交**

```bash
git add rag/src/main/java/com/wuyunbin/rag/service/DocumentService.java rag/src/test/java/com/wuyunbin/rag/service/DocumentServiceTest.java
git commit -m 'feat: rebuild chunks when pipeline version changes'
```

### Task 4: 建立离线切片基线报告

**Files:**

- Create: `rag/src/test/resources/chunking/baseline-corpus.txt`
- Create: `rag/src/test/java/com/wuyunbin/rag/cleaning/ChunkingBaselineTest.java`
- Modify: `rag/src/test/java/com/wuyunbin/rag/cleaning/CleaningTextSplitterTest.java`

**Interfaces:**

- Consumes: 固定语料和假的 `EmbeddingModel`。
- Produces: 块数、最小长度、最大长度、平均长度、长度小于 100 的碎块率，以及 `chunk_id` 唯一性断言。

- [x]**Step 1: 准备固定语料**

`baseline-corpus.txt` 包含标题、普通段落、长句、列表、Markdown 表格、代码块、URL、跨主题句对。每类之间用空行隔开，总长度控制在
3000 到 6000 字符。

- [x]**Step 2: 写不变量断言**

断言所有切片非空、长度不超过 `max-chunk-size`、`chunk_id` 无重复、`chunk_index` 从 0 连续到 `chunk_total - 1`。

- [x]**Step 3: 输出可比较指标**

测试末尾打印一行 JSON 风格文本：
`CHUNKING_BASELINE {chunks:142,min:55,max:1000,avg:178.4,fragmentRate:0.32,strategy:legacy-greedy}`。

- [x]**Step 4: 运行并记录基线**

Run: `mvn -q -f rag/pom.xml -Dtest=ChunkingBaselineTest test`

Expected: PASS，并记录本机控制台的 `CHUNKING_BASELINE` 行。

- [x]**Step 5: 提交**

```bash
git add rag/src/test/resources/chunking/baseline-corpus.txt rag/src/test/java/com/wuyunbin/rag/cleaning/ChunkingBaselineTest.java
git commit -m 'test: add semantic chunking baseline metrics'
```

## 完成标准

- 默认配置仍产生与当前算法等价的切片。
- 每个切片都有 `chunk_id`、`chunk_pipeline_version`、`chunk_strategy`。
- 同文件同版本不重复导入，同文件新版本可以重建。
- 固定语料可以输出可比较的长度分布和碎块率。
- 回滚方式明确：恢复旧 `pipeline-version` 并重新导入旧文件，或恢复旧集合快照。
