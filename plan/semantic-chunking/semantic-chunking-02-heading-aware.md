# 标题与章节感知切片实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每个切片携带所属标题路径，并阻止相邻章节在语义合并阶段被错误拼接。

**Architecture:** 在 `StructuredSegmenter` 扫描文本时维护标题栈。每个 `PROSE`、`TABLE`、`CODE` 段保存 `SectionContext`，
`CleaningTextSplitter` 将其写入最终切片元数据。标题行先结束上一段，再开启新段，从结构上形成语义切片的硬边界。

**Tech Stack:** Java 21、Spring Boot 4.1.1、JUnit 5，不引入 OCR 或外部解析服务。

## Global Constraints

- 仅支持文本中的 Markdown ATX 标题 `#` 到 `######`。
- 围栏代码块优先于标题解析，`#` 开头的代码行不得被当作标题。
- 现有 `block_type`、`code_lang`、`chunk_index`、`chunk_total` 保持兼容。
- 新增元数据键固定为 `section_id`、`section_path`、`section_level`。
- 没有标题的文档写入空路径和级别 `0`，不能抛异常。
- 推荐先完成 `semantic-chunking-01-versioned-baseline.md`，以便新策略可重建和对比。

---

### Task 1: 建立标题上下文模型

**Files:**

- Create: `rag/src/main/java/com/wuyunbin/rag/cleaning/SectionContext.java`
- Create: `rag/src/main/java/com/wuyunbin/rag/cleaning/MarkdownHeadingParser.java`
- Test: `rag/src/test/java/com/wuyunbin/rag/cleaning/MarkdownHeadingParserTest.java`

**Interfaces:**

- Consumes: 单行文本。
- Produces: `Optional<Heading> parse(String line)`；`Heading(int level, String title)`；
  `SectionContext.from(List<Heading>)`。

```java
public record Heading(int level, String title) {
}

public record SectionContext(String sectionId, String sectionPath, int sectionLevel) {
    public static final SectionContext EMPTY = new SectionContext(", ", 0);
}
```

- [x] **Step 1: 写标题解析失败测试**

覆盖 `# 一级标题`、`### 三级标题 ###`、`####### 非标题`、`#紧贴文字`、`普通正文`。只有前两种返回标题，第三个级别超过 6
级，第四个缺少空格，第五个不是标题。

- [x] **Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=MarkdownHeadingParserTest test`

Expected: FAIL，`MarkdownHeadingParser` 不存在。

- [x] **Step 3: 实现 ATX 标题解析**

使用固定模式 `^(#{1,6})\\s+(.+?)\\s*#*\\s*$`。标题文本去掉尾部装饰性 `#`，但保留正文内部井号。

- [x] **Step 4: 生成标题路径和稳定 section ID**

标题栈按级别维护。生成 `sectionPath` 时使用 ` / ` 连接标题文本；生成 `sectionId` 时使用 SHA-256 计算 `sectionPath`
的小写十六进制摘要。栈为空时返回 `SectionContext.EMPTY`。

- [x] **Step 5: 验证嵌套路径**

输入 `# 学校 / ## 宿舍 / ### 熄灯`，最终路径必须是 `学校 / 宿舍 / 熄灯`，级别必须是 3。

- [x] **Step 6: 运行测试并提交**

Run: `mvn -q -f rag/pom.xml -Dtest=MarkdownHeadingParserTest test`

Expected: PASS。

```bash
git add rag/src/main/java/com/wuyunbin/rag/cleaning/SectionContext.java rag/src/main/java/com/wuyunbin/rag/cleaning/MarkdownHeadingParser.java rag/src/test/java/com/wuyunbin/rag/cleaning/MarkdownHeadingParserTest.java
git commit -m 'feat: parse markdown heading context'
```

### Task 2: 让结构化分段携带标题上下文

**Files:**

- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/TextSegment.java:8-20`
- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/StructuredSegmenter.java:32-79`
- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/StructuredSegmenter.java:95-131`
- Test: `rag/src/test/java/com/wuyunbin/rag/cleaning/StructuredSegmenterTest.java`

**Interfaces:**

- Consumes: `MarkdownHeadingParser`、`SectionContext`。
- Produces: `TextSegment(SegmentType type, String content, String codeLang, SectionContext section)`；所有工厂方法都接受或默认传入
  `SectionContext`。

- [x] **Step 1: 写分段失败测试**

固定输入为“一级标题、正文、二级标题、表格、代码块”。断言表格和代码块的 `sectionPath` 都包含最近标题，且二级标题下正文不再属于一级标题正文段。

- [x] **Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=StructuredSegmenterTest test`

Expected: FAIL，`TextSegment` 没有 `section` 字段。

- [x] **Step 3: 扩展 TextSegment**

保留旧的 `prose(String)`、`table(String)`、`code(String, String)` 工厂，并新增带 `SectionContext` 的重载。原工厂默认传
`SectionContext.EMPTY`，避免一次性破坏调用方。

- [x] **Step 4: 在扫描循环中维护标题栈**

处理顺序固定为：代码围栏、标题行、表格行、普通正文。遇到标题时先 `flushProse` 和 `resolvePendingRows`，再更新标题栈，然后把标题行作为新正文段的起点。

- [x] **Step 5: 让表格和代码继承当前上下文**

`resolvePendingRows` 生成表格时传入当前 `section`；代码块开始时同时捕获当前 `section`，闭合围栏后写入该段的元数据。

- [x] **Step 6: 运行测试并提交**

Run: `mvn -q -f rag/pom.xml -Dtest=StructuredSegmenterTest test`

Expected: PASS。

```bash
git add rag/src/main/java/com/wuyunbin/rag/cleaning/TextSegment.java rag/src/main/java/com/wuyunbin/rag/cleaning/StructuredSegmenter.java rag/src/test/java/com/wuyunbin/rag/cleaning/StructuredSegmenterTest.java
git commit -m 'feat: attach section context during segmentation'
```

### Task 3: 禁止语义合并跨越标题边界

**Files:**

- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java:54-73`
- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java:76-89`
- Test: `rag/src/test/java/com/wuyunbin/rag/cleaning/CleaningTextSplitterTest.java`

**Interfaces:**

- Consumes: `TextSegment.section()` 和 `ChunkingProperties.getPipelineVersion()`。
- Produces: 每个最终切片含 `section_id`、`section_path`、`section_level`；同一章节的切片共享 `section_id`。

- [x] **Step 1: 写跨章节失败测试**

使用两个标题各带一段正文，配置相似度阈值 `0.0` 使旧算法倾向合并。断言输出中不存在同时包含两个标题的切片。

- [x] **Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=CleaningTextSplitterTest#doesNotMergeAcrossHeadingBoundaries test`

Expected: FAIL，旧实现可能把两段标题正文合并。

- [x] **Step 3: 在最终元数据中写入章节信息**

```java
SectionContext section = segment.section();
metadata.put(section_id, section.sectionId());
metadata.put(section_path, section.sectionPath());
metadata.put(section_level, section.sectionLevel());
```

- [x] **Step 4: 保持策略兼容**

当 `section_path` 为空时写入空字符串；当章节过滤关闭或旧策略运行时，字段仍存在但不参与排序与过滤。

- [x] **Step 5: 验证表格和代码元数据**

断言表格分段的每个切片带相同 `section_path`；代码切片除 `code_lang` 外也带相同章节路径。

- [x] **Step 6: 运行测试并提交**

Run: `mvn -q -f rag/pom.xml -Dtest=CleaningTextSplitterTest,StructuredSegmenterTest test`

Expected: PASS。

```bash
git add rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java rag/src/test/java/com/wuyunbin/rag/cleaning/CleaningTextSplitterTest.java
git commit -m 'feat: preserve heading boundaries in semantic chunks'
```

## 验证样本

实施完成后，用以下最小文本做端到端导入验证：

```text
# 学校
学校实行冬季作息。

## 宿舍
宿舍晚上十一点熄灯。

## 食堂
食堂早上六点开放。
```

验收要求：三个章节至少生成三个可定位切片；“宿舍”切片不能包含“食堂”正文；每个切片 `section_path` 分别以“学校”“学校 /
宿舍”“学校 / 食堂”开头。

## 完成标准

- 标题路径在正文、表格、代码切片中一致存在。
- 标题变化形成硬边界，语义相似度不能覆盖该边界。
- 无标题文档行为不变。
- `chunk_pipeline_version` 提升后才能导入新版本，旧版本可按方案 01 回滚。

