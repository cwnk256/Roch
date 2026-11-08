"""
RAG 检索参数（top-K / similarity-threshold）网格搜索评测脚本。

用法:
    1. 编辑同目录下的 test_set.json，填入测试问题与标准答案。
    2. 启动 RAG 后端（默认 http://localhost:8080）与 LM Studio（默认 http://localhost:1234）。
    3. 运行:  python rag_eval.py
    4. 查看输出的 rag_eval_results.csv 与终端汇总。

设计要点:
    - 不改项目代码：topK / threshold 通过 /api/chat/multi-turn 请求体逐次传入。
    - 用本地大模型（LM Studio）同时充当「答题模型」（通过 RAG 后端）与「评委」（直连 LM Studio）。
    - 评委从「正确性」和「忠实性」两个维度打分（0-5）。
"""

import csv
import json
import re
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

# ============ 配置 ============

# RAG 后端多轮问答接口
RAG_API_URL = "http://localhost:8080/api/chat/multi-turn"

# LM Studio 直连接口（评委用，不走 RAG 后端）
LM_STUDIO_URL = "http://localhost:1234/v1/chat/completions"
LM_STUDIO_MODEL = "google/gemma-4-e4b"  # 与 application.properties 中的对话模型一致

# 参数网格（基于历史日志：相似度分布在 0.51~0.70，故阈值重点扫 0.3~0.65）
TOPK_VALUES = [7,8,9]
THRESHOLD_VALUES = [0.3,0.35,0.4,0.45]

# 测试集路径
TEST_SET_PATH = Path(__file__).with_name("test_set_01.json")

# 输出
OUTPUT_CSV = Path(__file__).with_name("rag_eval_results_seconod.csv")

# 请求超时（秒）：本地模型可能较慢
REQUEST_TIMEOUT = 120

# 评委 temperature（0 = 输出稳定）
JUDGE_TEMPERATURE = 0.0

# 评委 max_tokens：gemma-4-e4b 是思考型模型，内部推理会先消耗大量 token，
# 太小会导致正式 JSON 还没输出就被截断（finish_reason=length，content 为空）
JUDGE_MAX_TOKENS = 2048

# 每个问题与评委请求之间的间隔（秒），避免本地模型过载
SLEEP_BETWEEN = 0.0


# ============ 评委提示词 ============

JUDGE_PROMPT = """你是检索问答质量评委。请严格依据给定材料判断回答质量，不要受你自身知识影响。

【问题】{question}
【标准答案】{golden_answer}
【模型回答】{model_reply}
【命中文档引用】{references}
（注意：引用仅提供文件名与相似度得分，不含原文内容）

请从两个维度各打 0~5 分（0=极差，5=完美），并各给一句不超过 30 字的理由：
1. 正确性：模型回答是否与标准答案在事实上一致？
2. 忠实性：模型回答是否基于标准答案所代表的知识作答，没有编造标准答案之外的事实、数字或细节？若回答与标准答案一致且无额外编造，应给高分。

只输出一行 JSON，不要输出任何其他文字，格式如下：
{{"correctness":0-5,"correctness_reason":"...","faithfulness":0-5,"faithfulness_reason":"..."}}
"""


# ============ 工具函数 ============


def load_test_set() -> list[dict[str, Any]]:
    if not TEST_SET_PATH.exists():
        raise FileNotFoundError(
            f"找不到测试集文件 {TEST_SET_PATH}，请先创建它（参考脚本末尾的示例）。"
        )
    with open(TEST_SET_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, list):
        raise ValueError("test_set.json 必须是一个数组。")
    return data


def _http_post_json(url: str, payload: dict) -> dict[str, Any] | None:
    """用标准库发 POST JSON 请求，返回解析后的 JSON 体。失败返回 None。"""
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:200]
        print(f"    [HTTP {e.code}] {body}")
        return None
    except Exception as e:  # noqa: BLE001
        print(f"    [请求失败] {e}")
        return None


def ask_rag(question: str, top_k: int, threshold: float) -> dict[str, Any] | None:
    """调用 RAG 后端的多轮问答接口，返回解析后的响应体。"""
    payload = {
        "message": question,
        "ragEnabled": True,
        "topK": top_k,
        "threshold": threshold,
    }
    return _http_post_json(RAG_API_URL, payload)


def judge(question: str, golden_answer: str, model_reply: str, references: list[dict]) -> dict[str, Any]:
    """调用本地大模型作为评委，返回 {correctness, faithfulness}。"""
    refs_text = "; ".join(
        f"{r.get('fileName','?')}#{r.get('chunkIndex','?')}(score={r.get('score', 0):.4f})"
        for r in references
    ) if references else "（无命中）"
    prompt = JUDGE_PROMPT.format(
        question=question,
        golden_answer=golden_answer or "（无标准答案，仅考察忠实性）",
        model_reply=model_reply,
        references=refs_text,
    )
    payload = {
        "model": LM_STUDIO_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": JUDGE_TEMPERATURE,
        "max_tokens": JUDGE_MAX_TOKENS,
    }
    resp = _http_post_json(LM_STUDIO_URL, payload)
    if resp is None:
        return {"correctness": 0, "faithfulness": 0, "raw": "judge request failed"}
    try:
        choice = resp["choices"][0]
        message = choice.get("message", {})
        content = message.get("content") or ""
        finish_reason = choice.get("finish_reason", "")
        # 思考型模型：content 为空时从 reasoning_content 兜底提取分数
        if not content.strip():
            reasoning = message.get("reasoning_content") or message.get("reasoning") or ""
            if reasoning.strip():
                result = parse_judge_response(reasoning)
                result["from_reasoning"] = True
                if finish_reason == "length":
                    result["truncated"] = True
                return result
            if finish_reason == "length":
                print("    [评委警告] 输出被截断且无内容，考虑增大 JUDGE_MAX_TOKENS")
            return {"correctness": 0, "faithfulness": 0, "raw": f"empty content (finish_reason={finish_reason})"}
        return parse_judge_response(content)
    except (KeyError, IndexError) as e:
        print(f"    [评委响应解析失败] {e}")
        return {"correctness": 0, "faithfulness": 0, "raw": str(resp)[:200]}


def parse_judge_response(content: str) -> dict[str, Any]:
    """从评委回复中解析 JSON；容错处理各种非 JSON 噪声。"""
    # 优先尝试直接解析
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        pass
    # 提取第一个 {...} 块
    match = re.search(r"\{.*\}", content, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            pass
    # 退化为正则提取数字
    correctness = _extract_score(content, "correctness")
    faithfulness = _extract_score(content, "faithfulness")
    return {"correctness": correctness, "faithfulness": faithfulness, "raw": content[:200]}


def _extract_score(text: str, key: str) -> int:
    m = re.search(rf'"?{key}"?\s*:\s*(\d)', text)
    return int(m.group(1)) if m else 0


def golden_hit(golden_file: str | None, references: list[dict]) -> bool:
    """金标文档是否出现在命中文档列表中（文件名子串匹配）。"""
    if not golden_file or not references:
        return False
    return any(golden_file in (r.get("fileName") or "") for r in references)


# ============ 主流程 ============


def run() -> None:
    test_set = load_test_set()
    print(f"测试集大小: {len(test_set)} 题")
    print(f"topK 网格: {TOPK_VALUES}")
    print(f"threshold 网格: {THRESHOLD_VALUES}")
    print(f"共 {len(TOPK_VALUES) * len(THRESHOLD_VALUES)} 组参数，每组 {len(test_set)} 题")
    print("=" * 80)

    rows: list[dict[str, Any]] = []

    for top_k in TOPK_VALUES:
        for threshold in THRESHOLD_VALUES:
            print(f"\n>>> topK={top_k}, threshold={threshold}")
            correctness_scores: list[float] = []
            faithfulness_scores: list[float] = []
            hit_count = 0
            empty_count = 0
            avg_refs: list[int] = []

            for idx, item in enumerate(test_set, 1):
                question = item["question"]
                golden_answer = item.get("golden_answer", "")
                golden_file = item.get("golden_file")

                resp = ask_rag(question, top_k, threshold)
                if resp is None:
                    continue

                reply = resp.get("reply", "")
                references = resp.get("references") or []
                avg_refs.append(len(references))
                if not references:
                    empty_count += 1
                if golden_hit(golden_file, references):
                    hit_count += 1

                judge_result = judge(question, golden_answer, reply, references)
                correctness_scores.append(judge_result["correctness"])
                faithfulness_scores.append(judge_result["faithfulness"])

                print(
                    f"    [{idx}/{len(test_set)}] "
                    f"refs={len(references)} hit={'Y' if golden_hit(golden_file, references) else 'N'} "
                    f"correct={judge_result['correctness']} faithful={judge_result['faithfulness']}"
                )
                if SLEEP_BETWEEN:
                    time.sleep(SLEEP_BETWEEN)

            n = len(correctness_scores) or 1
            rows.append({
                "topK": top_k,
                "threshold": threshold,
                "questions": n,
                "avg_correctness": round(sum(correctness_scores) / n, 3),
                "avg_faithfulness": round(sum(faithfulness_scores) / n, 3),
                "hit_rate": round(hit_count / n, 3),
                "empty_rate": round(empty_count / n, 3),
                "avg_refs": round(sum(avg_refs) / n, 2),
            })
            print(
                f"    => avg_correct={rows[-1]['avg_correctness']} "
                f"avg_faith={rows[-1]['avg_faithfulness']} "
                f"hit_rate={rows[-1]['hit_rate']} empty_rate={rows[-1]['empty_rate']}"
            )

    # 写 CSV
    with open(OUTPUT_CSV, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"\n详细结果已写入 {OUTPUT_CSV}")

    # 选最优：按 avg_correctness 降序，再 avg_faithfulness 降序，再 avg_refs 升序
    best = sorted(
        rows,
        key=lambda r: (-r["avg_correctness"], -r["avg_faithfulness"], r["avg_refs"]),
    )[0]
    print("\n" + "=" * 80)
    print("推荐最优组合（正确性优先，其次忠实性，再其次命中数越少越好）:")
    print(f"  topK = {best['topK']}")
    print(f"  similarity-threshold = {best['threshold']}")
    print(f"  avg_correctness = {best['avg_correctness']}")
    print(f"  avg_faithfulness = {best['avg_faithfulness']}")
    print(f"  hit_rate = {best['hit_rate']}")
    print(f"  empty_rate = {best['empty_rate']}")
    print("=" * 80)


if __name__ == "__main__":
    run()
