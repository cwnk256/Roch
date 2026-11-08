# 语义切片版本化与评测基线 — 执行状态

> 对应计划：[semantic-chunking-01-versioned-baseline.md](semantic-chunking-01-versioned-baseline.md)
> 执行日期：2026-09-12 | 状态：**已完成（全部 4 个任务 + 回归通过）**

## 执行结果总览

| Task | 内容                          | 状态   | 提交        |
|------|-----------------------------|------|-----------|
| 1    | 外置切片参数（ChunkingProperties）  | ✅ 完成 | `5e14a0a` |
| 2    | 写入稳定切片元数据（ChunkIdFactory）   | ✅ 完成 | `a85cd4a` |
| 3    | 支持同文件策略版本重建（ImportDecision） | ✅ 完成 | `a9f3cb5` |
| 4    | 建立离线切片基线报告                  | ✅ 完成 | `ac10180` |

## 测试结果（最终回归）

| 测试类                      | 用例数    | 结果       |
|--------------------------|--------|----------|
| ChunkIdFactoryTest       | 4      | PASS     |
| ChunkingBaselineTest     | 1      | PASS     |
| CleaningTextSplitterTest | 2      | PASS     |
| DocumentServiceTest      | 7      | PASS     |
| SemanticTextSplitterTest | 1      | PASS     |
| **合计**                   | **15** | **全部通过** |

## 基线指标（固定语料，FakeEmbeddingModel 离线）

```
CHUNKING_BASELINE {chunks:40,min:30,max:250,avg:73.9,fragmentRate:0.95,strategy:legacy-greedy}
```

> 注意：碎块率偏高是 FakeEmbeddingModel 随机向量导致（句子间相似度很少 ≥0.7，无法合并）。
> 该基线用于验证不变量与跨版本指标可比性；真实质量评估需在 LM Studio 嵌入可用时另行记录。

## 新增元数据（snake_case，写入每个切片）

- `chunk_id`：SHA-256（doc_md5|pipeline_version|block_type|chunk_index|content），64 位小写十六进制
- `chunk_pipeline_version`：默认 `legacy-v1`
- `chunk_strategy`：默认 `legacy-greedy`

## 导入行为变化

- 同 MD5 + 同版本 → 跳过（不变）
- 同 MD5 + 版本不同 → **先写入新版本切片，再删除旧版本切片**（删除失败仅 log.error，不误报导入失败）
- 全新文档 → 直接导入（不变）
- 存量旧数据（无 `chunk_pipeline_version` 元数据）重新导入时走重建分支，自动补齐版本元数据

## 执行偏差记录（相对原计划）

1. **测试文件均为新建**：计划把 `SemanticTextSplitterTest`/`CleaningTextSplitterTest` 标注为 Modify，实际不存在，按 Create
   处理。
2. **Task 1 提交连带纳入 cleaning 包既有未跟踪文件**（CleaningTextSplitter 等 7 个文件），保证该 commit 可独立编译。
3. **跳过计划中 `SemanticTextSplitter.java:49-59` 的修改**：元数据只在 CleaningTextSplitter 终态循环写入即可，
   `SemanticTextSplitter.apply()` 不在导入链路上。
4. **CleaningTextSplitter 注入 ChunkingProperties**（计划未写明接线方式），`doc_md5` 从 document metadata 读取，缺失时回退
   `document.getId()`。
5. **新增安全默认值**：`ChunkingProperties` 字段初始化为默认值（`@Value` 仅在 Spring 环境覆盖），`SemanticTextSplitter` 构造器对
   `maxChunkSize`/`embedBatchSize` 做 `max(1, ...)` 下界保护——执行中发现直接实例化时默认值缺失会触发切片循环死循环。
6. **计划列出的 Modify `CleaningTextSplitterTest`（Task 4）未做额外修改**：Task 2 已新建并覆盖所需断言。
7. **执行环境**：无 mvn/无 wrapper → 使用 `C:\Users\29802\.m2\wrapper\dists\apache-maven-3.9.11`；无 JDK 21 → openjdk-24 +
   `--release 21`。

## 测试基建（新增）

- `rag/src/test/java/com/wuyunbin/rag/testutil/FakeEmbeddingModel.java`：确定性假嵌入模型（文本哈希 → 8 维向量），全测试离线。
- `rag/src/test/resources/chunking/baseline-corpus.txt`：固定评测语料（约 3050 字符，覆盖标题/正文/列表/表格/代码块/URL/跨主题段落）。

## 回滚方式

- 配置回滚：`rag.chunking.pipeline-version` 改回旧值并重新导入文件（自动重建）。
- 代码回滚：`git revert a85cd4a a9f3cb5`（元数据与重建逻辑），`5e14a0a`/`ac10180` 为配置外置与测试，可保留。
- 数据回滚：Milvus 快照恢复（如已做快照）。

## 遗留事项

- 生产数据首次重新导入时，`DeleteParam` 按表达式删除旧版本切片；建议导入后抽查 `chunk_pipeline_version` 分布确认清理生效。
- Mockito 自附加 agent 警告（JDK 24）：可后续在 surefire 配置 `-javaagent` 消除，非阻塞。
