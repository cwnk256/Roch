package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.dto.RetrievedChunk;
import com.wuyunbin.rag.service.generation.ClaimVerifier.AnchorSpan;
import com.wuyunbin.rag.service.generation.ClaimVerifier.Citation;
import com.wuyunbin.rag.service.generation.SectionGenerator.QuestionEvidence;

import java.util.*;

record SectionEvidence(Attempt initial, Attempt retry, Map<Integer, String> questions) {
    record TableAnswer(String text, Citation citation) {}

    record Attempt(ClaimVerifier.EvidenceOptions options, Map<Integer, Set<String>> questionIds) {
        Citation citation(Integer question, String id) {
            return question != null && id != null && questionIds.getOrDefault(question, Set.of()).contains(id)
                    ? options.citations().get(id) : null;
        }

        Optional<TableAnswer> uniqueTableAnswer(int number, String question) {
            Map<List<String>, TableAnswer> matches = new LinkedHashMap<>();
            for (String id : questionIds.getOrDefault(number, Set.of())) {
                Citation citation = options.citations().get(id);
                if (citation.physicalFacts() == null) continue;
                for (String statement : citation.physicalFacts()) {
                    if (!StructuredEvidence.exactlyAnswersTableQuestion(question, statement)) continue;
                    var row = List.of(Objects.toString(citation.tableCaption(), ""), citation.quote(), statement);
                    matches.putIfAbsent(row, new TableAnswer(statement, citation));
                }
            }
            return matches.size() == 1 ? Optional.of(matches.values().iterator().next()) : Optional.empty();
        }

        List<AnchorSpan> spans(List<RetrievedChunk> chunks, boolean numbered) {
            if (!numbered) return options.spans(chunks);
            List<AnchorSpan> spans = new ArrayList<>();
            questionIds.forEach((number, ids) -> {
                Map<String, Citation> allowed = new LinkedHashMap<>();
                ids.forEach(id -> allowed.put(id, options.citations().get(id)));
                var scoped = new ClaimVerifier.EvidenceOptions("", allowed);
                scoped.spans(chunks).forEach(span -> spans.add(span.forQuestion(number)));
            });
            return List.copyOf(spans);
        }
    }

    static SectionEvidence allocate(ClaimVerifier.EvidenceOptions available, List<RetrievedChunk> chunks,
                                    List<QuestionEvidence> questions, TableQuestionScope tableScope, int budget) {
        Map<Integer, String> questionTexts = new LinkedHashMap<>();
        Map<Integer, List<String>> ranked = new LinkedHashMap<>();
        Map<String, String> lines = new LinkedHashMap<>();
        for (QuestionEvidence question : questions) {
            questionTexts.put(question.number(), question.text());
            var rankedIds = rankedIds(available.citations(), chunks, question, tableScope);
            ranked.put(question.number(), rankedIds);
            rankedIds.forEach(id -> lines.computeIfAbsent(id,
                    key -> ClaimVerifier.renderOption(key, available.citations().get(key))));
        }

        // 先给各题留出一条可用证据的空间，再把有余量的首选替换为排序更靠前的证据。
        Map<Integer, String> primary = new LinkedHashMap<>();
        for (var question : ranked.entrySet()) {
            Set<String> used = new HashSet<>(primary.values());
            var shortest = question.getValue().stream().min(Comparator.comparingInt(id -> cost(id, used, lines)));
            if (shortest.isPresent() && cost(shortest.get(), used, lines) <= budget - characters(used, lines)) {
                primary.put(question.getKey(), shortest.get());
            }
        }
        for (var question : ranked.entrySet()) {
            var others = new HashSet<>(primary.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(question.getKey())).map(Map.Entry::getValue).toList());
            int remaining = budget - characters(others, lines);
            question.getValue().stream().filter(id -> cost(id, others, lines) <= remaining).findFirst()
                    .ifPresent(id -> primary.put(question.getKey(), id));
        }

        Set<String> used = new HashSet<>(primary.values());
        int remaining = budget - characters(used, lines);
        // 两题共用同一条首选后，省出的空间仍优先给缺少首选的题目。
        for (var question : ranked.entrySet()) {
            if (primary.containsKey(question.getKey())) continue;
            for (String id : question.getValue()) {
                int cost = cost(id, used, lines);
                if (cost > remaining) continue;
                primary.put(question.getKey(), id);
                used.add(id);
                remaining -= cost;
                break;
            }
        }

        Map<Integer, Set<String>> offered = new LinkedHashMap<>();
        ranked.forEach((number, ids) -> {
            // 同一原文可以为确实检索到它的多个问题提供引用，无需在提示中重复原文。
            Set<String> choices = new LinkedHashSet<>();
            if (primary.containsKey(number)) choices.add(primary.get(number));
            offered.put(number, choices);
        });
        boolean numbered = tableScope.hasQuestionNumbers();
        Attempt retry = attempt(offered, available.citations(), lines);
        for (var question : ranked.entrySet()) {
            Set<String> choices = offered.get(question.getKey());
            for (String id : question.getValue()) {
                if (choices.contains(id)) continue;
                int cost = cost(id, used, lines);
                if (cost > remaining) continue;
                choices.add(id);
                used.add(id);
                remaining -= cost;
                if (numbered) break;
            }
        }
        Attempt initial = attempt(offered, available.citations(), lines);
        return new SectionEvidence(initial, numbered ? retry : initial,
                Collections.unmodifiableMap(questionTexts));
    }

    private static List<String> rankedIds(Map<String, Citation> citations, List<RetrievedChunk> chunks,
                                          QuestionEvidence question, TableQuestionScope tableScope) {
        Map<String, Integer> chunkRanks = new HashMap<>();
        for (String chunkId : question.candidateChunkIds()) chunkRanks.putIfAbsent(chunkId, chunkRanks.size());
        var eligible = new LinkedHashMap<String, Citation>();
        citations.entrySet().stream().filter(entry -> {
            Citation citation = entry.getValue();
            return tableScope.allows(question.number(), citation) && citation.source() > 0
                    && citation.source() <= chunks.size() && chunkRanks.containsKey(chunks.get(citation.source() - 1).chunkId());
        }).sorted(Comparator.comparingInt(entry -> chunkRanks.get(chunks.get(entry.getValue().source() - 1).chunkId())))
                .forEach(entry -> eligible.put(entry.getKey(), entry.getValue()));
        return ClaimVerifier.rankOptions(eligible, question.text()).stream().map(Map.Entry::getKey).toList();
    }

    private static int cost(String id, Set<String> used, Map<String, String> lines) {
        return used.contains(id) ? 0 : lines.get(id).length();
    }

    private static int characters(Set<String> ids, Map<String, String> lines) {
        return ids.stream().mapToInt(id -> lines.get(id).length()).sum();
    }

    private static Attempt attempt(Map<Integer, Set<String>> offered, Map<String, Citation> citations,
                                    Map<String, String> lines) {
        Map<Integer, Set<String>> questionIds = new LinkedHashMap<>();
        Map<String, Citation> visible = new LinkedHashMap<>();
        var prompt = new StringBuilder();
        offered.forEach((number, ids) -> {
            questionIds.put(number, Collections.unmodifiableSet(new LinkedHashSet<>(ids)));
            ids.forEach(id -> visible.putIfAbsent(id, citations.get(id)));
            prompt.append("第 ").append(number).append(" 题可用证据编号：")
                    .append(ids.isEmpty() ? "无" : String.join("、", ids)).append('\n');
        });
        if (!visible.isEmpty()) {
            prompt.append('\n');
            visible.keySet().forEach(id -> prompt.append(lines.get(id)));
        }
        return new Attempt(new ClaimVerifier.EvidenceOptions(prompt.toString(), Collections.unmodifiableMap(visible)),
                Collections.unmodifiableMap(questionIds));
    }
}
