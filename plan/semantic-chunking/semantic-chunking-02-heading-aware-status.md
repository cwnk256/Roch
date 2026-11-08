# 标题与章节感知切片 — 实施状态

> 对应计划：[semantic-chunking-02-heading-aware.md](./semantic-chunking-02-heading-aware.md)
> 执行日期：2026-09-14
> 总体状态：✅ 完成（29/29 测试通过）

## 提交记录

| Commit    | 内容                                                   |
|-----------|------------------------------------------------------|
| `43ba8fd` | feat: parse markdown heading context                 |
| `08840ac` | feat: attach section context during segmentation     |
| `09bbbf7` | feat: preserve heading boundaries in semantic chunks |
| `269d858` | chore: bump pipeline version to heading-aware-v2     |

## 任务完成情况

### Task 1: 建立标题上下文模型 ✅

- 新增 [Heading.java](../../../rag/src/main/java/com/wuyunbin/rag/cleaning/Heading.java)：`(level, title)` record。
- 新增 [SectionContext.java](../../../rag/src/main/java/com/wuyunbin/rag/cleaning/SectionContext.java)：
  `(sectionId, sectionPath, sectionLevel)`，`from(List<Heading>)` 以 ` / ` 拼接路径并计算 SHA-256 摘要；空栈返回 `EMPTY`。
- 新增 [MarkdownHeadingParser.java](../../../rag/src/main/java/com/wuyunbin/rag/cleaning/MarkdownHeadingParser.java)：
  固定模式 `^(#{1,6})\s+(.+?)\s*#*\s*$`，去尾部装饰井号、保留正文内部井号。
-
测试 [MarkdownHeadingParserTest.java](../../../rag/src/test/java/com/wuyunbin/rag/cleaning/MarkdownHeadingParserTest.java)
：6 个用例（含 5 个计划用例 + 内部井号 + 空栈 + ID 稳定性）。

### Task 2: 结构化分段携带标题上下文 ✅

- [TextSegment.java](../../../rag/src/main/java/com/wuyunbin/rag/cleaning/TextSegment.java) 增加 `section` 组件，
  旧工厂（无 section）保留并默认 `EMPTY`，新增带 section 的重载。
- [StructuredSegmenter.java](../../../rag/src/main/java/com/wuyunbin/rag/cleaning/StructuredSegmenter.java)：
  扫描顺序固定为 **围栏 → 标题 → 表格 → 正文**；维护标题栈，遇同级/更高级标题先弹栈；
  标题行先结算上一段再压栈，并作为新正文段起点；表格段、代码段在生成时捕获当前章节快照。
- 测试 [StructuredSegmenterTest.java](../../../rag/src/test/java/com/wuyunbin/rag/cleaning/StructuredSegmenterTest.java)
  ：4 个用例
  （表格/代码继承章节、栈弹出、标题硬边界、无标题文档 EMPTY）。

### Task 3: 章节元数据 + 跨标题硬边界 ✅

- [CleaningTextSplitter.java](../../../rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java)：
  `ChunkDraft` 增加 section；每个最终切片写入 `section_id`、`section_path`、`section_level`；
  清洗关闭分支与无标题文档写入空字符串和级别 0。
- 跨标题边界测试 `doesNotMergeAcrossHeadingBoundaries`：阈值压到 0.0，
  验证「学校/宿舍/食堂」三章节至少 3 个切片、无切片横跨宿舍与食堂正文、路径分别正确。
- 表格/代码切片共享章节路径测试通过。

### 版本提升（计划外补步）✅

[application.properties](../../../rag/src/main/resources/application.properties)：

```properties
rag.chunking.pipeline-version=heading-aware-v2
rag.chunking.strategy=heading-aware-v1
```

## 与计划的偏差（评审时发现并修正）

1. **计划代码示例编译错误**：`SectionContext.EMPTY = new SectionContext(", ", 0)` 参数数量不匹配（record 有 3 个组件），
   实现为 `new SectionContext("", "", 0)`。
2. **结算顺序纠正**：计划文字为"先 flushProse 和 resolvePendingRows"，
   实际必须先 `resolvePendingRows` 后 `flushProse`——否则未构成表格的行会在 flush 之后归还 prose 缓冲区，泄漏到下一章节。
   与既有围栏处理顺序保持一致。
3. **Task 3 无需改 SemanticTextSplitter**：标题处在分段层已形成硬边界，
   `CleaningTextSplitter` 对每个 PROSE 段独立调用 `splitProse()`，语义合并不可能跨段；Task 3 仅需写元数据，跨边界测试作为验收。
4. **补充版本提升步骤**：完成标准要求版本提升但任务列表缺该步，已补提交 `269d858`。
   `ChunkingProperties` 上的 `@Value` 默认值保持 `legacy-v1/legacy-greedy`（尊重方案 01 的默认行为约束），仅改部署配置。

## 验收样本结果

计划中的最小文本（# 学校 / ## 宿舍 / ## 食堂）由
`CleaningTextSplitterTest#doesNotMergeAcrossHeadingBoundaries` 覆盖：

- 三个章节生成 3 个以上可定位切片；
- 「宿舍」切片不含「食堂」正文（阈值 0.0 下仍成立）；
- `section_path` 分别为 `学校`、`学校 / 宿舍`、`学校 / 食堂`。

## 测试

```
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
```

执行环境：Maven 3.9.11（`~/.m2/wrapper/dists`）、JDK 22（source/target 21）。
单元测试均不访问 LM Studio / Milvus / 网络。

## 数据迁移与回滚

- 存量 Milvus 数据的 `chunk_pipeline_version=legacy-v1`，应用以 `heading-aware-v2` 启动后，
  **重新导入旧文件会自动走 `REBUILD_NEW_VERSION`**：先写新切片，再删除旧版本切片（删除失败只记日志，不影响新数据）。
- 回滚：把 `rag.chunking.pipeline-version` / `rag.chunking.strategy` 改回
  `legacy-v1` / `legacy-greedy` 并重新导入；或恢复 Milvus 集合快照。

## 遗留事项

- 新元数据键（section_*）目前只写入、未参与检索过滤或排序；后续若要按章节聚合/过滤，需要在检索层扩展。
- 真实 bge-m3 嵌入下端到端导入验证未在本机构建流程中执行（单测使用 FakeEmbeddingModel）；
  建议启动应用后对 `jmu新生手册.txt` 做一次重新导入并在 Milvus GUI 抽查 `section_path`。
