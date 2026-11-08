# 父子分块与检索上下文扩展实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用较小的子块完成精确向量召回，用所属父块为模型提供完整上下文。

**Architecture:** 切片阶段先按章节和 `parent-max-chars` 生成父块，再复用现有语义切片生成子块。子块元数据保存 `parent_id` 和
`parent_text`，不新增 Milvus collection。检索后按 `parent_id` 去重，保留最高分子块排序，并把父块正文送入 prompt。

**Tech Stack:** Java 21、Spring AI 2.0.0、Milvus、JUnit 5、Mockito。

## Global Constraints

- 父块功能默认关闭：`rag.chunking.parent.enabled=false`。
- 开启后 `max-chunk-size` 仍限制子块；`parent-max-chars` 限制父块正文。
- 不修改 Milvus schema，不新增 collection。
- 每个子块必须继续拥有 `chunk_id` 和 `chunk_index`。
- 检索日志仍记录实际命中的子块，不把父块伪装成命中项。
- 推荐依赖方案 01 和方案 02；未完成方案 02 时，父块按连续正文段聚合。

---

### Task 1: 生成父块与子块关系

**Files:**

- Create: `rag/src/main/java/com/wuyunbin/rag/cleaning/ChunkDraft.java`
- Create: `rag/src/main/java/com/wuyunbin/rag/cleaning/ParentChildChunker.java`
- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/ChunkingProperties.java`
- Modify: `rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java:46-89`
- Modify: `rag/src/main/resources/application.properties`
- Test: `rag/src/test/java/com/wuyunbin/rag/cleaning/ParentChildChunkerTest.java`

**Interfaces:**

- Consumes: 有序 `ChunkDraft` 列表、`SectionContext`、父块长度上限。
- Produces: `List<ChunkDraft>`，每个 draft 增加 `parentId`、`parentText`、`parentIndex`、`parentTotal`。

- [ ] **Step 1: 写父子关系失败测试**

输入四个同一章节的子切片，设置 `parent-max-chars` 只能容纳前两个。断言第一个父组包含子块 0 和 1，第二个父组包含子块 2 和
3，所有子块都有 `parent_id` 和 `parent_text`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=ParentChildChunkerTest test`

Expected: FAIL，`ParentChildChunker` 不存在。

- [ ] **Step 3: 增加配置**

```properties
rag.chunking.parent.enabled=false
rag.chunking.parent.max-chars=3000
```

- [ ] **Step 4: 按章节和长度划分父块**

父块边界优先级固定为：章节变化、父块长度到达 `parent-max-chars`、正文段结束。单个子块超过 `parent-max-chars`
时，该子块独占一个父块，不能截断正文。

为让 `ParentChildChunker` 复用现有数据，先把 `CleaningTextSplitter` 内的 `private record ChunkDraft` 提升为包级
`ChunkDraft`，并增加可选父块字段；旧构造路径继续保留。

- [ ] **Step 5: 生成稳定 parent_id**

`parent_id` 使用 `doc_md5 + pipeline_version + section_id + parent_index` 计算 SHA-256。相同文档、相同策略版本、相同父块位置必须得到相同
ID。

- [ ] **Step 6: 写入最终元数据并提交**

`CleaningTextSplitter` 在创建最终 `Document` 时写入 `parent_id`、`parent_text`、`parent_index`、`parent_total`。功能关闭时不写这四个键。

Run: `mvn -q -f rag/pom.xml -Dtest=ParentChildChunkerTest,CleaningTextSplitterTest test`

Expected: PASS。

```bash
git add rag/src/main/java/com/wuyunbin/rag/cleaning/ParentChildChunker.java rag/src/main/java/com/wuyunbin/rag/cleaning/ChunkingProperties.java rag/src/main/java/com/wuyunbin/rag/cleaning/CleaningTextSplitter.java rag/src/main/resources/application.properties rag/src/test/java/com/wuyunbin/rag/cleaning/ParentChildChunkerTest.java
git commit -m 'feat: attach parent context to child chunks'
```

### Task 2: 检索后按父块去重

**Files:**

- Create: `rag/src/main/java/com/wuyunbin/rag/retrieval/ParentContextExpander.java`
- Modify: `rag/src/main/java/com/wuyunbin/rag/service/MultiTurnChatService.java:58-95`
- Modify: `rag/src/main/java/com/wuyunbin/rag/service/MultiTurnChatService.java:216-226`
- Test: `rag/src/test/java/com/wuyunbin/rag/retrieval/ParentContextExpanderTest.java`

**Interfaces:**

- Consumes: `List<Document> childHits`、`maxParents`、`maxPromptChars`。
- Produces: `List<ParentContext> expand(...)`，每个 `ParentContext` 含 `parentId`、`text`、`bestScore`、`childRanks`。

- [ ] **Step 1: 写去重和排序失败测试**

构造四种子块命中：两个属于 P1、一个属于 P2、一个没有父块。断言结果只含一个 P1，顺序按 `bestScore` 降序，无父块仍作为独立上下文保留。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -q -f rag/pom.xml -Dtest=ParentContextExpanderTest test`

Expected: FAIL，`ParentContextExpander` 不存在。

- [ ] **Step 3: 去重策略**

同一个 `parent_id` 只保留最高分子块的代表分数，`childRanks` 记录所有命中子块的 rank。没有 `parent_id` 的切片使用自身
`chunk_id` 作为分组键。

- [ ] **Step 4: 控制 prompt 预算**

按 `bestScore` 降序选择父块，直到达到 `maxParents` 或 `maxPromptChars`。候选父块会超过字符预算时跳过，并继续检查更短候选。

- [ ] **Step 5: 接入问答上下文**

`MultiTurnChatService` 使用父块正文构造 system prompt。检索日志中的 `hits` 和响应 `references` 仍使用实际命中的子块，
`usedInPrompt` 以最终父块数量大于 0 为准。

- [ ] **Step 6: 验证并提交**

Run: `mvn -q -f rag/pom.xml -Dtest=ParentContextExpanderTest,MultiTurnChatServiceTest test`

Expected: PASS，相同父块只出现一次，prompt 字符数不超过预算。

```bash
git add rag/src/main/java/com/wuyunbin/rag/retrieval/ParentContextExpander.java rag/src/main/java/com/wuyunbin/rag/service/MultiTurnChatService.java rag/src/test/java/com/wuyunbin/rag/retrieval/ParentContextExpanderTest.java
git commit -m 'feat: expand retrieval hits with parent context'
```

### Task 3: 增加配置和端到端回归

**Files:**

- Modify: `rag/src/main/resources/application.properties`
- Modify: `rag/src/test/java/com/wuyunbin/rag/cleaning/ChunkingBaselineTest.java`
- Create: `rag/src/test/resources/evaluation/parent-child-questions.jsonl`

- [ ] **Step 1: 增加检索预算配置**

```properties
rag.chat.rag.max-parents=4
rag.chat.rag.max-prompt-chars=12000
```

- [ ] **Step 2: 准备固定问题集**

每个 JSON 行包含 `question`、`expectedSectionPath`、`expectedAnswerFragment`。至少覆盖 10 个可从单一句子回答的问题和 5
个需要同章节多句合并的问题。

- [ ] **Step 3: 比较子块基线和父块策略**

同一问题集分别记录：命中子块数、去重父块数、送入 prompt 字符数、答案片段是否出现在最终上下文中。

- [ ] **Step 4: 运行回归**

Run: `mvn -q -f rag/pom.xml -Dtest=ParentChildChunkerTest,ParentContextExpanderTest,ChunkingBaselineTest test`

Expected: PASS，父块策略的答案片段覆盖率高于子块基线，且 prompt 字符数不超过 `max-prompt-chars`。

## 验收指标

| 指标        | 通过条件                                            |
|-----------|-------------------------------------------------|
| 子块召回      | 不低于关闭父块时的命中质量                                   |
| 父块去重      | 同一 `parent_id` 在一个 prompt 中只出现一次                |
| 上下文覆盖     | 固定问题集的答案片段覆盖率高于仅使用子块                            |
| prompt 预算 | 不超过 `max-prompt-chars`                          |
| 回退        | `rag.chunking.parent.enabled=false` 后问答行为回到子块模式 |

## 完成标准

- 父块默认关闭，不会影响现有导入和问答。
- 开启后不新增 collection，只新增切片元数据。
- 检索日志、响应 references 和父块 prompt 三者语义清晰且互不混淆。
- 重新导入同一文件时依赖方案 01 的版本机制生成新父块元数据。
