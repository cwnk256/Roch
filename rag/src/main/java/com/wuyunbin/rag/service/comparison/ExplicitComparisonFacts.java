package com.wuyunbin.rag.service.comparison;

import com.wuyunbin.rag.dto.RetrievedChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses explicit sentence-level facts. Unrecognized forms still go through the model extractor. */
final class ExplicitComparisonFacts {
    private static final Pattern SENTENCE = Pattern.compile("[^。！？；\\r\\n]+[。！？；]?");
    private static final String NUMBER = "[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?";
    private static final Pattern PERIODIC = Pattern.compile("周期|间隔|频次|频率|多久|每隔");
    private static final Pattern OPERATION = Pattern.compile("校准|校验|校正|巡检|检修|检查|检测|维护|保养");
    private static final Pattern INTERVAL = Pattern.compile("(" + NUMBER + ")\\s*(小时|星期|个月|天|日|周|月|年)");
    private static final String CODE = "[A-Za-z]+[-_]?\\d+[A-Za-z\\d-]*";
    private static final Pattern EQUIPMENT = Pattern.compile(CODE + "\\s*(?:设备|装置|机组|仪器|泵)?|(?:设备|装置|机组|仪器|泵)\\s*" + CODE);
    private static final Pattern ENVIRONMENT = Pattern.compile("(?:^|在|于|，|,)\\s*([^，,。；在于]{1,18}?(?:环境|工况|条件|状态|模式))(?:下|中|的|时|[，,])");
    private static final Pattern BUDGET = Pattern.compile("预算|经费|支出|开支|开销|支用");
    private static final Pattern FINANCIAL = Pattern.compile(
            "^([^，,。；]{1,32}?(?:项目|工程|计划|部门|公司))\\s*(\\d{4}\\s*年(?:度|\\s*(?:[上下]半年|第?[一二三四1-4]季度|\\d{1,2}\\s*月))?)\\s*"
            + "([\\p{IsHan}]{0,15}?(?:预算(?:额)?|经费|实际支出|实际开支|实际支用|支出|开支|开销))\\s*(?:总额|金额)?\\s*(?:为|是|共计|合计|计|达|约为|约|不超过|不少于|不得超过|不得低于|至少|至多|[：:])?\\s*"
            + "(?:人民币\\s*)?(" + NUMBER + ")\\s*(亿元|万元|千元|元)[。；]?$");
    private static final Pattern POWER = Pattern.compile("断电|切断电源|切断供电|关闭电源|断开电源");
    private static final Pattern SAFETY_MODAL = Pattern.compile("必须|务必|不得|禁止|严禁|不得不|应当|无需|不必|可以|建议|须|应");
    private static final Pattern SERVICE = Pattern.compile("检修|维修|维护|保养|作业");

    record Selection(List<ComparisonCandidate> facts, boolean needsModel, Predicate<String> relevant) {}

    static Selection select(String dimension, List<RetrievedChunk> chunks) {
        boolean cycle = PERIODIC.matcher(dimension).find();
        boolean money = BUDGET.matcher(dimension).find();
        boolean safety = POWER.matcher(dimension).find();
        if ((!cycle && !money && !safety) || (cycle && money) || (safety && (cycle || money))) {
            return new Selection(List.of(), true, quote -> true);
        }
        Predicate<String> relevant = quote -> {
            if (money) return BUDGET.matcher(quote).find();
            if (safety) return POWER.matcher(quote).find();
            String operation = match(OPERATION, dimension);
            return (PERIODIC.matcher(quote).find() || quote.contains("每") || quote.contains("定期"))
                    && (operation.isEmpty() || operationKey(operation).equals(operationKey(match(OPERATION, quote))));
        };
        var facts = new LinkedHashMap<String, ComparisonCandidate>();
        boolean unresolved = false;
        for (int index = 0; index < chunks.size(); index++) {
            Matcher sentences = SENTENCE.matcher(chunks.get(index).content());
            while (sentences.find()) {
                String quote = sentences.group().strip();
                if (quote.length() > 1000 || !relevant.test(quote)) continue;
                int source = index + 1;
                ComparisonCandidate candidate = money ? amount(source, quote)
                        : cycle ? interval(source, quote) : safety(source, quote);
                if (candidate == null) unresolved = true;
                else facts.putIfAbsent(quote, candidate);
            }
        }
        return new Selection(facts.values().stream().limit(ComparisonExtractor.MAX_FACTS).toList(),
                unresolved && facts.size() < ComparisonExtractor.MAX_FACTS, relevant);
    }

    static List<ComparisonCandidate> merge(Selection selection, List<ComparisonCandidate> inferred) {
        List<ComparisonCandidate> facts = new ArrayList<>(selection.facts());
        for (ComparisonCandidate candidate : inferred) {
            if (facts.size() >= ComparisonExtractor.MAX_FACTS) break;
            if (selection.relevant().test(candidate.quote()) && facts.stream().noneMatch(f -> f.quote().equals(candidate.quote()))) {
                facts.add(candidate);
            }
        }
        return facts;
    }

    private static ComparisonCandidate amount(int source, String quote) {
        Matcher amount = FINANCIAL.matcher(quote);
        if (!amount.matches()) return null;
        return new ComparisonCandidate(source, quote, amount.group(1), amount.group(2), "",
                amount.group(3), amount.group(4), amount.group(5));
    }

    private static ComparisonCandidate interval(int source, String quote) {
        Matcher duration = INTERVAL.matcher(quote);
        String object = match(EQUIPMENT, quote);
        String metric = match(OPERATION, quote);
        if (!duration.find() || object.isBlank() || metric.isBlank()) return null;
        String value = duration.group(1);
        String unit = duration.group(2);
        // Multiple durations or an unparsed condition need interpretation in context.
        if (duration.find()) return null;
        Matcher environment = ENVIRONMENT.matcher(quote);
        String condition = environment.find() ? environment.group(1) : "";
        if ((!condition.isEmpty() && environment.find()) || quote.matches(".*(?:如果|若|仅限|除非|除外|但|且|以及|并且|年度|季度|半年).*")) return null;
        return new ComparisonCandidate(source, quote, object, "", condition, metric, value, unit);
    }

    private static ComparisonCandidate safety(int source, String quote) {
        String object = match(EQUIPMENT, quote);
        String activity = match(SERVICE, quote);
        if (object.isEmpty() || activity.isEmpty() || !SAFETY_MODAL.matcher(quote).find()) return null;
        return new ComparisonCandidate(source, quote, object, "", "", activity, quote, "");
    }

    private static String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().strip() : "";
    }

    static String operationKey(String operation) {
        return switch (operation) {
            case "校验", "校正" -> "校准";
            default -> operation;
        };
    }

    private ExplicitComparisonFacts() {}
}
