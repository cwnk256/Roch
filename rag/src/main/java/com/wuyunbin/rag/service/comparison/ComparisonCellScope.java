package com.wuyunbin.rag.service.comparison;

import com.wuyunbin.rag.dto.ReportConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

record ComparisonCellScope(String dimension, String constraints, Set<String> includedCodes, Set<String> excludedCodes) {
    private static final Pattern SENTENCES = Pattern.compile("[。！？；;\\r\\n]+");
    private static final Pattern CONNECTORS = Pattern.compile("[，,、]|以及|并且|和|与");
    private static final Pattern CODE = Pattern.compile("[A-Za-z]+[-_]?\\d+[A-Za-z\\d-]*");
    private static final String ENTITY = "项目|园区|压缩机|货梯|设备|仪器|机组|装置|厂区";
    private static final Pattern SUBJECT = Pattern.compile(
            "^(?:对照|核对|比较|对比|说明|检查)\\s*(.{1,24}?(?:" + ENTITY + "))");
    private static final Pattern ENTITY_NAME = Pattern.compile(ENTITY);
    private static final Pattern PRESENTATION = Pattern.compile(
            "^(?:按维度列出|保留原(?:值|文|单位)|判断换算|比较固定值时|分清|说明各方原文|各方原文)");
    private static final Pattern INCLUDE = Pattern.compile("^(?:只|仅)纳入\\s*(" + CODE + ")(?=\\s|[\\p{IsHan}]|$)");
    private static final Pattern EXCLUDE = Pattern.compile("^(?:明确)?排除\\s*(" + CODE + ")(?=\\s|[\\p{IsHan}]|$)");
    private static final Pattern LINK = Pattern.compile("仅|只|排除|限于|必须|不得|只要|除外");
    private static final Pattern SCOPE_VERB = Pattern.compile("^(?:仅|只)?(?:对照|核对|比较|对比|说明|检查)?");
    private static final String SUBJECT_PREFIX = "比较对象为";

    static ComparisonCellScope from(String dimension, ReportConfiguration config) {
        String marker = AnchoredComparisonFacts.metricKey(dimension);
        var siblings = config.comparisonDimensions().stream().filter(other -> !other.equals(dimension))
                .map(AnchoredComparisonFacts::metricKey).filter(other -> !other.equals(marker)).toList();
        var constraints = new LinkedHashSet<String>();
        var included = new LinkedHashSet<String>();
        var excluded = new LinkedHashSet<String>();
        List<String> instructions = new java.util.ArrayList<>();
        if (config.instruction() != null) instructions.add(config.instruction());
        config.sections().stream().filter(section -> "matrix".equals(section.sectionId()))
                .map(ReportConfiguration.Section::instruction).filter(java.util.Objects::nonNull)
                .forEach(instructions::add);
        var markers = Stream.concat(Stream.of(marker), siblings.stream()).toList();
        for (String instruction : instructions) {
            for (String sentence : SENTENCES.split(instruction)) {
                List<String> parts = clauses(sentence, markers).toList();
                if (parts.size() > 1) {
                    var subject = SUBJECT.matcher(parts.getFirst().strip());
                    if (subject.find() && parts.stream().anyMatch(part -> AnchoredComparisonFacts.metricKey(part).contains(marker))
                            && parts.stream().noneMatch(part -> namesSubject(part, marker))) {
                        constraints.add(SUBJECT_PREFIX + subject.group(1).strip());
                    }
                }
                for (String part : parts) {
                    String clause = part.strip();
                    if (clause.isEmpty() || PRESENTATION.matcher(clause).find()) continue;
                    String key = AnchoredComparisonFacts.metricKey(clause);
                    if (siblings.stream().anyMatch(key::contains) && !key.contains(marker)) continue;
                    if (key.equals(marker)) continue;
                    // Keep explicit selectors and qualifiers; a bare connective fragment adds no scope.
                    if (!key.contains(marker) && clause.length() < 4 && !LINK.matcher(clause).find()) continue;
                    constraints.add(clause);
                    if (!clause.contains("或") && !clause.contains("和") && !clause.contains("与")) {
                        var include = INCLUDE.matcher(clause);
                        var exclude = EXCLUDE.matcher(clause);
                        if (include.find()) included.add(include.group(1).toLowerCase(Locale.ROOT));
                        if (exclude.find()) excluded.add(exclude.group(1).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return new ComparisonCellScope(dimension, String.join("\n", constraints), Set.copyOf(included), Set.copyOf(excluded));
    }

    private static boolean namesSubject(String clause, String marker) {
        String key = AnchoredComparisonFacts.metricKey(clause);
        int metricStart = key.indexOf(marker);
        return metricStart > 0 && ENTITY_NAME.matcher(key.substring(0, metricStart)).find();
    }

    private static Stream<String> clauses(String sentence, List<String> markers) {
        String key = AnchoredComparisonFacts.metricKey(sentence);
        if (markers.stream().filter(key::contains).count() < 2) return Stream.of(sentence);
        List<String> clauses = new java.util.ArrayList<>();
        var connector = CONNECTORS.matcher(sentence);
        int start = 0;
        while (connector.find()) {
            String previous = sentence.substring(start, connector.start());
            String previousKey = AnchoredComparisonFacts.metricKey(previous);
            if (markers.stream().anyMatch(previousKey::contains)) {
                clauses.add(previous);
                start = connector.end();
            }
        }
        clauses.add(sentence.substring(start));
        return clauses.stream();
    }

    String prompt() {
        return prompt(constraints);
    }

    private String prompt(String applicableConstraints) {
        return applicableConstraints.isBlank() ? dimension
                : dimension + "\n仅核对当前维度。下列是对当前维度适用的范围约束，每个事实单独判断。"
                + "\n" + applicableConstraints;
    }

    String verificationScope(AnchoredComparisonFacts.Selection selection) {
        if (!selection.literalFieldsProven()) return prompt();
        String unresolved = constraints.lines().filter(clause -> !proven(clause, selection.candidate()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return unresolved.isBlank() ? "" : prompt(unresolved);
    }

    private boolean proven(String clause, ComparisonCandidate fact) {
        String text = scopeKey(clause);
        String object = scopeKey(fact.object());
        if (text.startsWith(SUBJECT_PREFIX)) return object.equals(text.substring(SUBJECT_PREFIX.length()));
        if (text.startsWith("只纳入") || text.startsWith("仅纳入"))
            return !includedCodes.isEmpty() && singleEquipmentSelector(clause, object, true);
        if (text.startsWith("明确排除") || text.startsWith("排除"))
            return !excludedCodes.isEmpty() && singleEquipmentSelector(clause, object, false);
        String metric = scopeKey(dimension);
        String subjectAndScope = text.replaceFirst("(?:比较要求|要求)$", "");
        if (!subjectAndScope.endsWith(metric)) return false;
        subjectAndScope = SCOPE_VERB.matcher(subjectAndScope.substring(0, subjectAndScope.length() - metric.length()))
                .replaceFirst("");
        for (String originalField : List.of(object, scopeKey(fact.period()), scopeKey(fact.condition()))) {
            if (!originalField.isBlank()) subjectAndScope = subjectAndScope.replaceFirst(Pattern.quote(originalField), "");
        }
        return subjectAndScope.matches("[在于的下时内]*");
    }

    private static String scopeKey(String text) {
        return AnchoredComparisonFacts.metricKey(text).toLowerCase(Locale.ROOT).replaceAll("(?<=\\d{4}年)度", "");
    }

    private boolean singleEquipmentSelector(String clause, String object, boolean include) {
        var match = (include ? INCLUDE : EXCLUDE).matcher(clause);
        if (!match.find()) return false;
        String code = match.group(1).toLowerCase(Locale.ROOT);
        if (!(include ? includedCodes : excludedCodes).contains(code)
                || !clause.substring(match.end()).strip().matches("(?:设备|装置|仪器|机组|压缩机|货梯|泵)?")) return false;
        return Pattern.compile("(?<![a-z0-9_-])" + Pattern.quote(code) + "(?![a-z0-9_-])")
                .matcher(object).find() == include;
    }

    boolean permits(String quote) {
        Set<String> codes = CODE.matcher(quote).results().map(m -> m.group().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return java.util.Collections.disjoint(codes, excludedCodes)
                && (includedCodes.isEmpty() || includedCodes.stream().anyMatch(codes::contains));
    }
}
