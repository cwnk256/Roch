package com.wuyunbin.rag.service.generation;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 独立于模型的事实核验下限，按原引文中的值及其归属判定结构化断言。 */
final class StructuredEvidence {
    record Passage(String quote, String chunk, String originalPage, boolean ocrText) {}
    private record Amount(BigDecimal value, String unit) {
        boolean equalsValue(Amount other, String metric) {
            return value.compareTo(other.value) == 0 && (unit.equals(other.unit)
                    || (scoreMetric(metric) && List.of("", "分").contains(unit)
                    && List.of("", "分").contains(other.unit)));
        }
    }
    private record Assertion(String entity, String metric, Amount amount, Integer rank) {}
    private record Scope(String entity, String metric) {}

    private static final String DIGITS = "[0-9一二三四五六七八九十〇零两]+";
    private static final Pattern RANK = Pattern.compile(
            "(?:(?:排\\s*名(?:为)?|位列|名列|位居|排在|(?:全球|世界)(?=\\s*第))\\s*(?:全球|世界)?\\s*第?\\s*("
            + DIGITS + ")(?:名|位)?|第\\s*(" + DIGITS + ")\\s*(?:名|位)|(榜首|居首|首位|冠军|次席|亚军))");
    private static final Pattern ORDINAL = Pattern.compile("第\\s*(" + DIGITS + ")");
    private static final Pattern SERIES = Pattern.compile("分别(?:为|是|达到|得分为)?|依次(?:为|是|达到|得分为)?");
    private static final String NUMBER = "[+\\-−]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?";
    private static final String AREA_UNIT = "平方千米|平方公里|平方米|平方厘米|平方毫米|万亩|公顷|亩";
    private static final Pattern OCR_AREA_DECIMAL = Pattern.compile(
            "(?<=\\p{IsHan})(\\h*[0-9]+)\\h+．\\h+([0-9]+)(?=\\h*(?:" + AREA_UNIT + "))");
    private static final Pattern QUANTITY = Pattern.compile(
            "(?<![a-zA-Z0-9.,+\\-−])(" + NUMBER + ")(?![0-9]|[.,][0-9])\\s*"
            + "(" + AREA_UNIT + "|亿元|万元|千元|元|万美元|美元|万欧元|欧元|百分点|个百分点|%|"
            + "小时|分钟|毫秒|微秒|纳秒|秒钟|秒|星期|周|个月|月|年|天|日|分|"
            + "千瓦|毫瓦|瓦|千米|公里|厘米|毫米|微米|纳米|米|吨|毫克|千克|公斤|克|毫升|升|[a-zA-Zµμ]+)?");
    private static final Pattern COUNTRY = Pattern.compile(
            "中国|美国|英国|德国|法国|日本|加拿大|澳大利亚|意大利|西班牙|荷兰|瑞士|韩国|瑞典|印度|"
            + "比利时|奥地利|以色列|丹麦|新加坡|俄罗斯|巴西|新西兰|挪威|芬兰|葡萄牙|波兰|南非");
    private static final Pattern ENTITY = Pattern.compile(COUNTRY.pattern()
            + "|(?i:(?<![a-z0-9])[a-z]+[-_]?\\d+[a-z\\d-]*)(?![a-zA-Z0-9])"
            + "|[甲乙丙丁](?:项目|泵|装置|设备)|(?:项目|设备|装置|机组|仪器|泵)[甲乙丙丁]");
    private static final Pattern METRIC = Pattern.compile(
            "研究前沿热度指数|热度指数|领域汇总|贡献度|影响度|实际支出|实际开支|支出|开支|经费|预算|"
            + "校准(?:周期|间隔)?|校验(?:周期|间隔)?|校正(?:周期|间隔)?|巡检(?:周期|间隔)?|"
            + "检查(?:周期|间隔)?|检修(?:周期|间隔)?|维护(?:周期|间隔)?|保养(?:周期|间隔)?|"
            + "响应耗时|响应时限|到场时限|到场时间|保修期|质保期");
    private static final Pattern REQUIRED = Pattern.compile("必须|务必|应当|须要|(?<![无不])须");
    private static final Pattern RECOMMENDED = Pattern.compile("建议|推荐|宜");
    private static final Pattern ACTION = Pattern.compile("校准|校验|校正|巡检|检查|检修|维护|保养|断电|送电|上报|提交");
    private static final Pattern DECIMAL_COLUMNS = Pattern.compile("\\d+\\.\\d+\\s+\\d+\\.\\d+");
    private static final Pattern TABLE_CAPTION = Pattern.compile("^(?:#+\\s*)?表\\s*\\d+.*");
    private static final Pattern SCORE_ROW = Pattern.compile("^([^0-9]+?)(?=[+\\-−\\d])(.+)$");
    private static final Pattern NUMBER_IN_ROW = Pattern.compile(NUMBER);

    private StructuredEvidence() {}

    static String insufficiency(String claim, List<Passage> passages) {
        String normalized = normalize(claim);
        if (REQUIRED.matcher(normalized).find()) {
            String action = first(ACTION, normalized);
            boolean backed = passages.stream().anyMatch(p -> clauses(normalize(p.quote())).stream()
                    .anyMatch(c -> REQUIRED.matcher(c).find() && !RECOMMENDED.matcher(c).find()
                            && (action.isEmpty() || c.contains(action))));
            if (!backed) return "引文缺少与陈述相同的义务强度及适用动作。";
        }
        String explicitEntity = field(normalized, "对象");
        String explicitMetric = field(normalized, "指标");
        for (String clause : clauses(normalized)) {
            List<Assertion> alternative = parallelAssertions(clause);
            if (alternative != null && alternative.isEmpty()) return "陈述的并列对象、指标和数值无法逐项对应。";
            List<Assertion> assertions = alternative != null ? alternative : assertions(clause, explicitEntity, explicitMetric);
            var scopes = assertions.stream().collect(Collectors.groupingBy(a -> new Scope(a.entity(), a.metric())));
            for (List<Assertion> sameScope : scopes.values()) {
                if (passages.stream().noneMatch(p -> sameScope.stream().allMatch(a -> supports(a, p)))) {
                    return "引文没有在同一对象和指标下证实陈述的数值、单位或名次。";
                }
            }
        }
        return null;
    }

    private static boolean supports(Assertion assertion, Passage passage) {
        // 保留存档引文的字形和偏移，仅在核验中连接 OCR 分隔的面积小数。
        String source = passage.ocrText() ? OCR_AREA_DECIMAL.matcher(passage.quote()).replaceAll("$1.$2") : passage.quote();
        String quote = normalize(source);
        if (quote.contains("|") || DECIMAL_COLUMNS.matcher(quote).find()) {
            String indexed = normalize(passage.chunk());
            String original = passage.originalPage() == null ? indexed : normalize(passage.originalPage());
            return supportsTable(assertion, quote, indexed, original)
                    || supportsTable(assertion, quote, original, original)
                    || supportsMarkdown(assertion, quote, original);
        }
        for (String clause : clauses(quote)) {
            List<Assertion> parallel = parallelAssertions(clause);
            for (Assertion candidate : parallel != null ? parallel : assertions(clause, "", "")) {
                if (assertion.rank() != null ? !assertion.rank().equals(candidate.rank())
                        : candidate.amount() == null || !assertion.amount().equalsValue(candidate.amount(), assertion.metric())) {
                    continue;
                }
                if (consistent(assertion.entity(), candidate.entity(), clause)
                        && consistent(assertion.metric(), candidate.metric(), clause)) return true;
            }
        }
        return false;
    }

    private static List<Assertion> parallelAssertions(String clause) {
        Matcher series = SERIES.matcher(clause);
        if (!series.find()) return null;
        String prefix = clause.substring(0, series.start());
        List<String> entities = ENTITY.matcher(prefix).results().map(m -> m.group().toLowerCase(Locale.ROOT)).distinct().toList();
        List<String> metrics = METRIC.matcher(prefix).results().map(m -> metricKey(m.group())).distinct().toList();
        if (entities.size() < 2 && metrics.size() < 2) return null;
        if (entities.size() > 1 && metrics.size() > 1) return List.of();
        boolean byEntity = entities.size() > 1;
        int width = byEntity ? entities.size() : metrics.size();
        String body = clause.substring(series.end());
        Matcher rank = RANK.matcher(body);
        boolean hasRanks = rank.find();
        List<Integer> ranks = hasRanks ? ORDINAL.matcher(body.substring(rank.start())).results()
                .map(m -> rankValue(m.group(1))).toList() : List.of();
        String values = hasRanks ? body.substring(0, rank.start()) : body;
        List<Amount> quantities = QUANTITY.matcher(values).results()
                .map(m -> amount(m.group(1), m.group(2) == null && prefix.contains("得分") ? "分" : m.group(2))).toList();
        if ((!quantities.isEmpty() && quantities.size() != width) || (hasRanks && ranks.size() != width)) return List.of();
        List<Assertion> result = new ArrayList<>();
        for (int column = 0; column < width; column++) {
            String entity = byEntity ? entities.get(column) : entities.isEmpty() ? "" : entities.getFirst();
            String metric = !byEntity ? metrics.get(column) : metrics.isEmpty() ? "" : metrics.getFirst();
            if (!quantities.isEmpty()) result.add(new Assertion(entity, metric, quantities.get(column), null));
            if (hasRanks && ranks.get(column) != null) result.add(new Assertion(entity, metric, null, ranks.get(column)));
        }
        return result;
    }

    private static List<Assertion> assertions(String clause, String explicitEntity, String explicitMetric) {
        List<Assertion> result = new ArrayList<>();
        List<int[]> rankSpans = new ArrayList<>();
        Matcher rank = RANK.matcher(clause);
        while (rank.find()) {
            Integer number = rank.group(3) != null
                    ? (List.of("次席", "亚军").contains(rank.group(3)) ? 2 : 1)
                    : rankValue(rank.group(1) == null ? rank.group(2) : rank.group(1));
            if (number != null) {
                result.add(new Assertion(entity(clause, rank.start(), explicitEntity),
                        metric(clause, rank.start(), explicitMetric), null, number));
                rankSpans.add(new int[]{rank.start(), rank.end()});
            }
        }
        Matcher quantity = QUANTITY.matcher(clause);
        while (quantity.find()) {
            int start = quantity.start(1);
            if (rankSpans.stream().anyMatch(span -> start >= span[0] && start < span[1])) continue;
            result.add(new Assertion(entity(clause, start, explicitEntity), metric(clause, start, explicitMetric),
                    amount(quantity.group(1), quantity.group(2) == null && clause.contains("得分") ? "分" : quantity.group(2)), null));
        }
        return result;
    }

    private static boolean supportsTable(Assertion fact, String quote, String tableText, String originalText) {
        if (fact.entity().isEmpty() || !scoreMetric(fact.metric())) return false;
        List<String> header = List.of();
        boolean scoreAndRank = false;
        String original = compact(originalText);
        for (String rawLine : tableText.lines().toList()) {
            String line = rawLine.strip();
            if (TABLE_CAPTION.matcher(line).matches()) {
                header = List.of();
                scoreAndRank = false;
                continue;
            }
            if (line.contains("排名") && (line.contains("得分") || line.contains("热度指数"))) {
                scoreAndRank = true;
                continue;
            }
            List<String> names = countryHeader(line);
            if (!names.isEmpty()) {
                header = scoreAndRank && original.contains(compact(line)) ? names : List.of();
                continue;
            }
            int column = header.indexOf(fact.entity());
            Matcher row = SCORE_ROW.matcher(line);
            if (column < 0 || !row.matches() || !fact.metric().equals(metricKey(first(METRIC, row.group(1))))) continue;
            List<String> values = NUMBER_IN_ROW.matcher(row.group(2)).results().map(m -> m.group()).toList();
            int width = header.size();
            if (values.size() < width || !original.contains(compact(line))) continue;
            if (fact.rank() == null) {
                String proof = compact(row.group(1)) + String.join("", values.subList(0, column + 1));
                if (compact(quote).contains(proof) && fact.amount().equalsValue(amount(values.get(column), "分"), fact.metric()))
                    return true;
            } else {
                List<String> ranks = values.subList(width, values.size());
                if (ranks.size() == 1 && ranks.getFirst().matches("[1-9]{" + width + "}")) {
                    ranks = Arrays.asList(ranks.getFirst().split(""));
                }
                if (ranks.size() == width && compact(quote).contains(compact(line))
                        && fact.rank().equals(rankValue(ranks.get(column)))) return true;
            }
        }
        return false;
    }

    private static List<String> countryHeader(String line) {
        List<String> names = COUNTRY.matcher(line).results().map(m -> m.group()).toList();
        int half = names.size() / 2;
        if (half < 2 || names.size() % 2 != 0 || !compact(line).equals(String.join("", names))) return List.of();
        List<String> first = names.subList(0, half);
        return first.equals(names.subList(half, names.size())) && first.stream().distinct().count() == half
                ? first : List.of();
    }

    private static boolean supportsMarkdown(Assertion fact, String quote, String original) {
        if (fact.entity().isEmpty() || fact.metric().isEmpty()) return false;
        List<String> header = List.of();
        for (String line : original.lines().toList()) {
            String row = line.strip();
            if (!row.startsWith("|") || !row.endsWith("|")) {
                header = List.of();
                continue;
            }
            List<String> cells = Arrays.stream(row.substring(1, row.length() - 1).split("\\|", -1))
                    .map(String::strip).toList();
            if (cells.stream().allMatch(c -> c.matches(":?-+:?"))) continue;
            if (!cells.isEmpty() && cells.getFirst().matches("国家(?:/地区)?|对象|主体|名称|设备")) {
                header = cells;
                continue;
            }
            if (cells.size() != header.size() || cells.isEmpty() || !compact(cells.getFirst()).equals(compact(fact.entity()))
                    || !compact(quote).contains(compact(row))) continue;
            for (int index = 1; index < cells.size(); index++) {
                String label = header.get(index);
                if (!fact.metric().equals(metricKey(first(METRIC, label)))) continue;
                if (fact.rank() != null && label.contains("排名") && fact.rank().equals(rankValue(cells.get(index)))) return true;
                if (fact.rank() == null && !label.contains("排名")) {
                    Matcher number = QUANTITY.matcher(cells.get(index));
                    if (number.matches() && fact.amount().equalsValue(amount(number.group(1), number.group(2)), fact.metric())) return true;
                }
            }
        }
        return false;
    }

    private static Amount amount(String value, String symbol) {
        BigDecimal number = new BigDecimal(value.replace(",", "").replace('−', '-'));
        String unit = Objects.toString(symbol, "");
        return switch (unit) {
            case "亿元" -> new Amount(number.scaleByPowerOfTen(8), "元");
            case "万元" -> new Amount(number.scaleByPowerOfTen(4), "元");
            case "千元" -> new Amount(number.scaleByPowerOfTen(3), "元");
            case "万美元" -> new Amount(number.scaleByPowerOfTen(4), "美元");
            case "万欧元" -> new Amount(number.scaleByPowerOfTen(4), "欧元");
            case "周", "星期" -> new Amount(number.multiply(BigDecimal.valueOf(604800)), "秒");
            case "天", "日" -> new Amount(number.multiply(BigDecimal.valueOf(86400)), "秒");
            case "小时" -> new Amount(number.multiply(BigDecimal.valueOf(3600)), "秒");
            case "分钟" -> new Amount(number.multiply(BigDecimal.valueOf(60)), "秒");
            case "秒钟" -> new Amount(number, "秒");
            case "个月" -> new Amount(number, "月");
            case "百分点", "个百分点" -> new Amount(number, "百分点");
            default -> new Amount(number, unit);
        };
    }

    private static Integer rankValue(String display) {
        if (display.matches("[0-9]{1,8}")) return Integer.valueOf(display);
        String digits = "零一二三四五六七八九";
        String value = display.replace('两', '二').replace('〇', '零');
        if (value.length() == 1 && digits.contains(value)) return digits.indexOf(value);
        int ten = value.indexOf('十');
        if (value.length() <= 3 && ten >= 0 && ten <= 1 && value.length() - ten <= 2) {
            int tens = ten == 0 ? 1 : digits.indexOf(value.charAt(0));
            int units = ten == value.length() - 1 ? 0 : digits.indexOf(value.charAt(ten + 1));
            if (tens > 0 && units >= 0) return tens * 10 + units;
        }
        return null;
    }

    private static String entity(String clause, int at, String explicit) {
        if (explicit.isEmpty()) return closest(ENTITY, clause, at).toLowerCase(Locale.ROOT);
        String known = first(ENTITY, explicit);
        return (known.isEmpty() ? compact(explicit) : known).toLowerCase(Locale.ROOT);
    }

    private static String metric(String clause, int at, String explicit) {
        if (explicit.isEmpty()) return metricKey(closest(METRIC, clause, at));
        String canonical = first(METRIC, explicit);
        return canonical.isEmpty() ? compact(explicit) : metricKey(canonical);
    }

    private static String metricKey(String value) {
        return switch (value) {
            case "领域汇总", "研究前沿热度指数" -> "热度指数";
            case "校验", "校正" -> "校准";
            default -> value.replaceFirst("(校准|校验|校正|巡检|检查|检修|维护|保养)(?:周期|间隔)$", "$1")
                    .replaceFirst("^(校验|校正)$", "校准");
        };
    }

    private static boolean scoreMetric(String metric) {
        return List.of("热度指数", "贡献度", "影响度").contains(metric);
    }

    private static boolean consistent(String expected, String found, String clause) {
        if (expected.isEmpty() || expected.equals(found)) return true;
        return found.isEmpty() && compact(clause).toLowerCase(Locale.ROOT).contains(expected);
    }

    private static String closest(Pattern pattern, String input, int at) {
        Matcher matcher = pattern.matcher(input);
        String previous = "";
        while (matcher.find()) {
            if (matcher.end() > at) return previous.isEmpty() ? matcher.group() : previous;
            previous = matcher.group();
        }
        return previous;
    }

    private static String field(String text, String name) {
        return first(Pattern.compile("(?<=^" + name + ":|;" + name + ":)[^;]+"), text);
    }

    private static String first(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group() : "";
    }

    private static List<String> clauses(String text) { return Arrays.asList(text.split("[。；;！？!?]")); }
    private static String normalize(String text) { return Normalizer.normalize(Objects.toString(text, ""), Normalizer.Form.NFKC); }
    private static String compact(String text) { return normalize(text).replaceAll("(?U)\\s+", ""); }
}
