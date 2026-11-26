package com.wuyunbin.rag.service.comparison;

import com.wuyunbin.rag.service.generation.ClaimVerifier.Citation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Recognizes complete, unambiguous clauses on the verified original anchor. */
final class AnchoredComparisonFacts {
    enum Type { NUMERIC, OBLIGATION, ANY }

    record Selection(Citation citation, ComparisonCandidate candidate, boolean literalFieldsProven, String semanticText) {}

    private static final String NUMBER = "[+\\-−]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?";
    private static final String DURATION = "工作日|自然日|小时|分钟|秒钟|星期|个月|秒|天|日|周|月|年";
    private static final String MONEY = "亿元|万元|千元|万美元|万欧元|美元|欧元|元";
    private static final String MONEY_METRIC = "[\\p{IsHan}]{0,10}?(?:预算(?:额)?|经费|实际支出|实际开支|支出|开支)";
    private static final String DURATION_METRIC =
            "(?:校准|校验|校正|巡检|检修|检查|检测|维修|维护|保养|交付|到货)(?:周期|间隔)"
            + "|(?:故障)?(?:到场|到达|响应)(?:时限|耗时|时间)|(?:替换)?备件(?:保修|质保)期|(?:保修|质保)期";
    private static final String OBJECT = "[^，,。；;|：:]+?";
    private static final String CONDITION = "(?:(?:在|于)?([^，,。；;|\\r\\n]{1,30}?)(?:下|内|时)?[，,]\\s*)?";
    private static final String PERIOD = "\\d{4}\\s*年(?:度|\\s*(?:[上下]半年|第?[一二三四1-4]季度|\\d{1,2}\\s*月))?";
    private static final String LIMIT = "不得超过|不超过|不得低于|不低于|至多|至少|不高于|不多于|不少于|"
            + "最高|最低|不小于|不大于|建议|约为|约|最多|最少";
    private static final String VALUE_LINK = "\\s*(?:为|是|计|共计|合计|[：:])?\\s*(?:" + LIMIT + ")?\\s*";
    private static final Pattern FINANCIAL = Pattern.compile("^" + CONDITION + "(" + OBJECT + ")\\s*(" + PERIOD
            + ")\\s*(?:的)?\\s*(" + MONEY_METRIC + ")" + VALUE_LINK + "(?:人民币\\s*)?(" + NUMBER
            + ")\\s*(" + MONEY + ")[。.!！]?$");
    private static final Pattern DURATION_VALUE = Pattern.compile("^" + CONDITION + "(" + OBJECT + ")\\s*的\\s*("
            + "(?:固定)?(?:" + DURATION_METRIC + "))" + VALUE_LINK + "(" + NUMBER + ")\\s*(" + DURATION + ")[。.!！]?$");
    private static final String POWER = "断电|切断电源|切断供电|关闭电源|断开电源";
    private static final String MODAL = "必须|务必|应当|须要|必须先|须|不得|严禁|禁止|无需|不必|不需要|不用|不应|不能|不可|允许|可以|建议";
    private static final String ACTIVITY = "校准|校验|校正|巡检|检修|检查|检测|维修|维护|保养|操作|作业";
    private static final Pattern ACTIVITY_NAME = Pattern.compile(ACTIVITY);
    private static final Pattern ACTIVITY_FIRST = Pattern.compile("^" + CONDITION + "(" + ACTIVITY + ")\\s*("
            + OBJECT + ")\\s*前\\s*((?:" + MODAL + ")\\s*(?:先)?(?:" + POWER + "))[。.!！]?$");
    private static final Pattern OBJECT_FIRST = Pattern.compile("^" + CONDITION + "(" + OBJECT + ")\\s*(?:的)?("
            + ACTIVITY + ")\\s*前\\s*((?:" + MODAL + ")\\s*(?:先)?(?:" + POWER + "))[。.!！]?$");
    private static final Pattern TYPED_DIMENSION = Pattern.compile("(?:预算|经费|支出|开支)$|(?:" + DURATION_METRIC + ")$");
    private static final Pattern PAGE_LAYOUT_PREFIX = Pattern.compile(
            "^(?:(?:续页\\h+[^\\r\\n]+|[一二三四五六七八九十]+、[^，,。；;：:\\r\\n]+(?:要求|规定|规程|说明))\\R{2,})+(?=[在于])");
    private static final Pattern CHANGE_OF_SCOPE = Pattern.compile("或者|除非|如果|另外|否则|分别|但|另行");
    private static final Pattern FRAGMENT = Pattern.compile("[。！？!?；;|\\r\\n]+|(?<!\\d),|,(?!\\d)");
    private static final Pattern QUANTITY = Pattern.compile("(?<![\\d.,+−-])" + NUMBER + "\\s*"
            + "(?:" + MONEY + "|" + DURATION + "|千克|公斤|克|千米|公里|米|毫米|升|毫升|箱|件|台|%)(?![0-9]|[.,][0-9])");
    private static final Pattern NO_NUMERIC_FACT = Pattern.compile(
            "(?:没有|未(?:提供|记录|披露|注明|规定|给出)|不含)[^，,。；;！？!?]{0,24}$");

    private AnchoredComparisonFacts() {}

    static String metricKey(String metric) {
        return compact(metric).replaceAll("固定|年度(?=采购预算|设备预算|预算)", "")
                .replaceAll("校验|校正", "校准")
                .replace("质保期", "保修期").replace("替换备件", "备件")
                .replace("切断电源", "断电").replace("断开电源", "断电")
                .replace("关闭电源", "断电").replace("切断供电", "断电");
    }

    static Type type(String dimension) {
        String metric = metricKey(dimension);
        if (metric.contains("断电")) return Type.OBLIGATION;
        return TYPED_DIMENSION.matcher(metric).find() ? Type.NUMERIC : Type.ANY;
    }

    static boolean possiblyRelevant(String dimension, String quote) {
        return switch (type(dimension)) {
            case NUMERIC -> mentionsNumericMetric(metricKey(dimension), quote);
            case OBLIGATION -> metricKey(quote).contains("断电");
            case ANY -> true;
        };
    }

    private static boolean mentionsNumericMetric(String metric, String quote) {
        String normalized = metricKey(quote);
        int index = normalized.indexOf(metric);
        if (index < 0) return false;
        if (QUANTITY.matcher(Normalizer.normalize(quote, Normalizer.Form.NFKC)).find()) return true;
        while (index >= 0) {
            // 无法解析的数值仍可能是相关事实。只排除明确没有给出该指标的语句。
            if (!NO_NUMERIC_FACT.matcher(normalized.substring(Math.max(0, index - 30), index)).find()) return true;
            index = normalized.indexOf(metric, index + metric.length());
        }
        return false;
    }

    static Selection literal(ComparisonCellScope scope, Citation citation) {
        List<Selection> recognized = new ArrayList<>();
        // An Excel anchor preserves the physical row, including labels next to a narrative cell.
        String[] parts = citation.cellLocation() == null ? new String[]{citation.quote()} : citation.quote().split("\\|");
        for (String part : parts) {
            // Page and section labels can precede the sentence in a complete original span.
            String text = PAGE_LAYOUT_PREFIX.matcher(part.strip()).replaceFirst("");
            if (text.isBlank() || CHANGE_OF_SCOPE.matcher(text).find()) continue;
            ComparisonCandidate parsed = parse(citation, text, scope);
            if (parsed != null) recognized.add(new Selection(citation, parsed, true, text));
        }
        return recognized.size() == 1 ? recognized.getFirst() : null;
    }

    private static ComparisonCandidate parse(Citation citation, String clause, ComparisonCellScope scope) {
        var financial = FINANCIAL.matcher(clause);
        if (financial.matches() && matches(scope.dimension(), financial.group(4))) {
            return candidate(citation, financial.group(2), financial.group(3), financial.group(1),
                    financial.group(4), financial.group(5), financial.group(6));
        }
        var duration = DURATION_VALUE.matcher(clause);
        if (duration.matches() && matches(scope.dimension(), duration.group(3))) {
            return candidate(citation, duration.group(2), "", duration.group(1),
                    duration.group(3), duration.group(4), duration.group(5));
        }
        var activity = ACTIVITY_FIRST.matcher(clause);
        if (activity.matches() && powerDimension(scope.dimension(), activity.group(2))) {
            return candidate(citation, activity.group(3), "", activity.group(1), activity.group(2), activity.group(4), "");
        }
        var object = OBJECT_FIRST.matcher(clause);
        if (object.matches() && powerDimension(scope.dimension(), object.group(3))) {
            return candidate(citation, object.group(2), "", object.group(1), object.group(3), object.group(4), "");
        }
        return null;
    }

    static Selection draft(ComparisonCellScope scope, Citation citation, Type type, String object, String period,
                           String condition, String metric, String value, String unit) {
        if (citation == null || (type(scope.dimension()) != Type.ANY && type(scope.dimension()) != type)) return null;
        Selection literal = literal(scope, citation);
        if (literal != null) return scope.equipmentScope(literal.candidate().object()).permitted() ? literal : null;
        if (metric == null || metric.isBlank() || value == null || value.isBlank()) return null;
        boolean inDimension = type(scope.dimension()) == Type.ANY || matches(scope.dimension(), metric)
                || type == Type.OBLIGATION && powerDimension(scope.dimension(), metric)
                        && metricKey(value).contains("断电");
        if (!inDimension) return null;
        ComparisonCandidate candidate = candidate(citation, object, period, condition, metric, value, unit);
        if (!scope.equipmentScope(candidate.object()).permitted()) return null;
        String quote = compact(citation.quote());
        if (!List.of(candidate.object(), candidate.period(), candidate.condition(), candidate.metric(), candidate.rawValue())
                .stream().allMatch(field -> quote.contains(compact(field)))) return null;
        if (type == Type.NUMERIC && (!Pattern.matches(NUMBER, compact(value)) || candidate.unit().isBlank()
                || !unambiguousQuantity(candidate, quote))) return null;
        if (type == Type.OBLIGATION && !Pattern.compile(MODAL).matcher(value).find()) return null;
        return new Selection(citation, candidate, false, citation.quote());
    }

    private static boolean unambiguousQuantity(ComparisonCandidate candidate, String quote) {
        String body = quote;
        for (String field : List.of(candidate.object(), candidate.period(), candidate.condition())) {
            if (!field.isBlank()) body = body.replaceFirst(Pattern.quote(compact(field)), "");
        }
        String value = compact(candidate.rawValue() + candidate.unit());
        return FRAGMENT.splitAsStream(body).anyMatch(fragment -> fragment.contains(compact(candidate.metric()))
                && Pattern.compile("(?<![\\d.,+−-])" + Pattern.quote(value) + "(?![0-9]|[.,][0-9])")
                        .matcher(fragment).find()
                && QUANTITY.matcher(fragment).results().count() <= 1
                && !fragment.matches(".*(?:分别|或|至\\d|[—–~～]).*"));
    }

    private static boolean matches(String dimension, String metric) {
        String wanted = metricKey(dimension);
        String actual = metricKey(metric);
        if (wanted.equals(actual)) return true;
        return List.of("预算", "支出", "经费", "开支", "保修期", "到场时限", "响应时限")
                .contains(wanted) && actual.endsWith(wanted);
    }

    private static boolean powerDimension(String dimension, String activity) {
        String key = metricKey(dimension);
        return key.contains("断电") && (metricKey(activity).equals("断电") || !ACTIVITY_NAME.matcher(key).find()
                || key.contains(metricKey(activity)));
    }

    private static ComparisonCandidate candidate(Citation citation, String object, String period, String condition,
                                                 String metric, String value, String unit) {
        return new ComparisonCandidate(citation.source(), citation.quote(), clean(object), clean(period), clean(condition),
                clean(metric), clean(value), clean(unit));
    }

    private static String clean(String text) {
        return Objects.toString(text, "").strip();
    }

    private static String compact(String text) {
        return Normalizer.normalize(clean(text), Normalizer.Form.NFKC).replaceAll("(?U)\\s+", "").replace('−', '-');
    }
}
