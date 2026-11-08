# 自适应边界与窗口策略实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用数据驱动的相邻句边界替代固定阈值贪心合并，并通过有限 overlap 降低切口两侧信息损失。

**Architecture:** `SemanticTextSplitter` 保留现有 `legacy-greedy` 算法，新增 `adaptive-boundary`
分支。新分支先计算相邻句相似度序列，再基于分位数和最小/最大块长度选择断点。overlap 只在非硬边界切片生成后添加，配置为 0
时行为完全关闭。

**Tech Stack:** Java 21、Spring AI `EmbeddingModel`、JUnit 5、确定性假向量。

## Global Constraints

- 默认策略保持 `legacy-greedy`，只有显式切到 `adaptive-boundary` 才启用新算法。
- 相似度仍使用 COSINE 语义，范围为 0 到 1。
- `max-chunk-size` 是硬上限，overlap 不得把切片推到上限之外。
- overlap 只从上一个已生成切片尾部复制完整句子，不能从半个句子开始。
- 表格和代码继续使用 `splitAtomic`，不得进入自适应边界分支。
- 推荐依赖方案 01 的配置和版本元数据。

---

### Task 1: 扩展策略配置

**Files:**

- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/ChunkingProperties.java`
- Modify: `rag/src/main/resources/application.properties`
- Test: `rag/src/test/java/com/wuyunbin/rag/cleaning/ChunkingPropertiesTest.java`

**Interfaces:**

- Consumes: `rag.chunking.*` 配置。
- Produces: `strategy`、`adaptiveMinThreshold`、`adaptiveMaxThreshold`、`iqrMultiplier`、`overlapChars`。

- [ ] **Step 1: 写默认值测试**

断言默认策略为 `legacy-greedy`，自适应下限为 `0.55`、上限为 `0.82`、IQR 倍数为 `1.5`、overlap 为 `0`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=ChunkingPropertiesTest test`

Expected: FAIL，新配置字段不存在。

- [ ] **Step 3: 新增配置**

```properties
rag.chunking.adaptive-min-threshold=0.55
rag.chunking.adaptive-max-threshold=0.82
rag.chunking.iqr-multiplier=1.5
rag.chunking.overlap-chars=0
```

配置校验要求：`0 <= min <= max <= 1`；`iqrMultiplier >= 0`；`overlapChars >= 0` 且小于 `max-chunk-size / 5`。

- [ ] **Step 4: 运行测试并提交**

Run: `mvn -q -f rag/pom.xml -Dtest=ChunkingPropertiesTest test`

Expected: PASS。

```bash
git add rag/src/main/java/com/wuyunbin/rag/cleaning/ChunkingProperties.java rag/src/main/resources/application.properties rag/src/test/java/com/wuyunbin/rag/cleaning/ChunkingPropertiesTest.java
git commit -m 'feat: add adaptive chunking configuration'
```

### Task 2: 建立断点决策模型

**Files:**

- Create: `rag/src/main/java/com/wuyunbin/rag/splitter/SentenceUnit.java`
- Create: `rag/src/main/java/com/wuyunbin/rag/splitter/BoundaryPlanner.java`
- Test: `rag/src/test/java/com/wuyunbin/rag/splitter/BoundaryPlannerTest.java`

**Interfaces:**

- Consumes: `List<SentenceUnit> units`、`ChunkingProperties`；`SentenceUnit` 含 `text`、`startOffset`、`endOffset`、`vector`。
- Produces: `List<Integer> planBreakAfter(...)`，返回应断开的上一个句子下标，下标按句子列表从 0 开始。

- [ ] **Step 1: 写确定性向量失败测试**

构造句子向量序列：A、A'、B、A''，其中 A/A'/A'' 属于同一主题，B 属于另一主题。断言输出只在 B 前后产生断点，且断点数量符合期望。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=BoundaryPlannerTest test`

Expected: FAIL，`BoundaryPlanner` 不存在。

- [ ] **Step 3: 计算相邻相似度**

对相邻句计算 COSINE，得到 `sim[i] = cosine(vector[i], vector[i + 1])`。三个句子的输入必须生成两个相似度值。

- [ ] **Step 4: 计算局部阈值**

算法固定为：

1. 对相似度排序并计算第一四分位数 `q1` 与四分位距 `iqr`。
2. 原始阈值等于 `q1 - 1.5 * iqr`。
3. 将阈值夹在 `adaptiveMinThreshold` 与 `adaptiveMaxThreshold` 之间。
4. `iqr == 0` 时使用 `adaptiveMinThreshold`，避免均匀相似度序列被过度切碎。
5. `sim[i] < threshold` 时标记候选断点。

该规则保证断点来自文档自身的相似度凹陷，同时用固定上下限控制过度切碎和跨主题合并。

- [ ] **Step 5: 加入长度约束**

规划器接收 `minChunkSize` 和 `maxChunkSize`。若两个候选断点之间的文本小于最小长度，取消较弱断点；若任何未断区间超过最大长度，在区间内相似度最低处强制加入断点。

- [ ] **Step 6: 运行测试并提交**

Run: `mvn -q -f rag/pom.xml -Dtest=BoundaryPlannerTest test`

Expected: PASS，边界按升序返回且没有重复下标。

```bash
git add rag/src/main/java/com/wuyunbin/rag/splitter/SentenceUnit.java rag/src/main/java/com/wuyunbin/rag/splitter/BoundaryPlanner.java rag/src/test/java/com/wuyunbin/rag/splitter/BoundaryPlannerTest.java
git commit -m 'feat: plan adaptive semantic boundaries'
```

### Task 3: 接入自适应切片和 overlap

**Files:**

- Modify: `rag/src/main/java/com/wuyunbin/rag/splitter/SemanticTextSplitter.java:63-74`
- Modify: `rag/src/main/java/com/wuyunbin/rag/splitter/SemanticTextSplitter.java:134-191`
- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java:56-62`
- Test: `rag/src/test/java/com/wuyunbin/rag/splitter/SemanticTextSplitterTest.java`

**Interfaces:**

- Consumes: `ChunkingProperties.strategy`、`BoundaryPlanner.planBreakAfter(...)`。
- Produces: `splitProse(String)` 在 `adaptive-boundary` 策略下返回带有限 overlap 的正文切片，在 `legacy-greedy` 策略下保持原行为。

- [ ] **Step 1: 写策略分流测试**

同一输入和同一假向量下，`legacy-greedy` 输出必须与改动前一致；`adaptive-boundary` 输出断开位置必须等于规划器结果。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=SemanticTextSplitterTest test`

Expected: FAIL，`adaptive-boundary` 分支不存在。

- [ ] **Step 3: 实现按断点组装切片**

按断点把句子加入 `StringBuilder`。当下一句会导致长度超过 `maxChunkSize` 时，即使没有语义断点也必须先结束当前切片。

- [ ] **Step 4: 添加句子级 overlap**

当 `overlapChars > 0` 且当前切片不是首个切片时，从前一切片尾部按句逆序取句子，直到达到 `overlapChars` 或达到上限。候选
overlap 与原文本长度之和超过 `maxChunkSize` 时，不添加完整 overlap。

- [ ] **Step 5: 验证最大长度和回退**

断言所有切片长度不超过 `maxChunkSize`；将策略改回 `legacy-greedy` 后，基线测试输出与方案 01 记录一致。

- [ ] **Step 6: 运行测试并提交**

Run: `mvn -q -f rag/pom.xml -Dtest=BoundaryPlannerTest,SemanticTextSplitterTest,ChunkingBaselineTest test`

Expected: PASS，且基线测试可分别输出旧策略和新策略指标。

```bash
git add rag/src/main/java/com/wuyunbin/rag/splitter/SemanticTextSplitter.java rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java rag/src/test/java/com/wuyunbin/rag/splitter/SemanticTextSplitterTest.java
git commit -m 'feat: add adaptive semantic chunk boundaries'
```

## 验收指标

使用方案 01 的固定语料，至少比较以下指标：

| 指标       | 通过条件                     |
|----------|--------------------------|
| 最大长度     | 不得超过 `max-chunk-size`    |
| 碎块率      | 不高于旧策略基线                 |
| `p50` 长度 | 高于旧策略基线                  |
| 跨主题样本    | 固定标注的跨主题句对不得出现在同一块       |
| 回退       | `legacy-greedy` 输出与旧基线一致 |

## 完成标准

- `adaptive-boundary` 与 `legacy-greedy` 可通过配置切换。
- overlap 为 `0` 时无重复正文；大于 `0` 时只复制完整句子。
- 新策略不改变接口和 Milvus schema。
- 调参发生在 `application.properties`，不再修改 `ChatConfig` 常量。
