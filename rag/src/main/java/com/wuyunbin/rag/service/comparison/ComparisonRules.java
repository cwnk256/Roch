package com.wuyunbin.rag.service.comparison;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import static com.wuyunbin.rag.service.comparison.ComparisonResult.*;

final class ComparisonRules {
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");
    private static final Pattern QUALIFIED = Pattern.compile("至少|至多|不超过|不得超过|以上|以下|不低于|不高于|范围|约|大约|建议|可选|不少于|不多于|或|可以|通常|一般|不固定|未固定|并非固定|取消|可变|可调整|暂定|预计|估计|大于|小于|[<>≤≥~～—–]|至\\s*\\d");
    private static final Pattern UPPER = Pattern.compile("不得超过|不能超过|不可超过|不超过|不得高于|不高于|不多于|至多|最多|上限|以下|<=|≤");
    private static final Pattern LOWER = Pattern.compile("不得低于|不能低于|不低于|不得少于|不少于|至少|最少|下限|以上|>=|≥");
    private static final Pattern SOFT_LIMIT = Pattern.compile("建议|宜|可选|预计|估计|通常|一般|大约|约|可能|暂定|可以|或者|或");
    private static final Pattern MODAL = Pattern.compile("不得不|必须|务必|应当|须要|须|应(?!急)|不得|严禁|禁止|无需|不必|不需要|不用|不应|不能|不可|允许|可以|可选|建议");
    private static final String EQUIPMENT_CODE = "([a-z]+[-_]?\\d+[a-z\\d-]*)";
    private ComparisonRules() {}

    static Fact fact(String id, ComparisonCandidate candidate, boolean evidenceVerified) {
        return fact(id, candidate, evidenceVerified, candidate.quote());
    }

    static Fact fact(String id, ComparisonCandidate candidate, boolean evidenceVerified, String originalQuote) {
        return fact(id, candidate, evidenceVerified, originalQuote, false);
    }

    static Fact fact(String id, ComparisonCandidate candidate, boolean evidenceVerified, String originalQuote, boolean strict) {
        return fact(id, candidate, evidenceVerified, originalQuote, strict, false);
    }

    static Fact fact(String id, ComparisonCandidate candidate, boolean evidenceVerified, String originalQuote,
                     boolean strict, boolean anchored) {
        return fact(id, candidate, evidenceVerified, originalQuote, strict, anchored, originalQuote);
    }

    static Fact fact(String id, ComparisonCandidate candidate, boolean evidenceVerified, String originalQuote,
                     boolean strict, boolean anchored, String semanticText) {
        String quote = clean(strict ? originalQuote : candidate.quote());
        String semantics = anchored ? clean(semanticText) : quote;
        String object = clean(candidate.object());
        String metric = clean(candidate.metric());
        String condition = clean(candidate.condition());
        String period = clean(candidate.period());
        String value = clean(candidate.rawValue());
        String unit = clean(candidate.unit());
        String quoteKey = strict ? normalizedText(quote) : compact(quote);
        boolean grounded = evidenceVerified && !value.isBlank() && !metric.isBlank()
                && List.of(object, metric, condition, period).stream().allMatch(field -> strict
                        ? equipmentKey(quoteKey).contains(equipmentKey(normalizedText(field)))
                        : quoteKey.contains(compact(field)));
        String parsedValue = strict ? normalizedText(value) : value;
        String parsedUnit = strict ? normalizedText(unit) : unit;
        grounded &= quoteKey.contains(strict ? parsedValue : compact(value))
                && quoteKey.contains(strict ? parsedUnit : compact(unit));
        String normalized = null;
        String normalizedUnit = null;
        boolean numeric = NUMBER.matcher(parsedValue).matches();
        if (numeric) {
            // Anchor signs, decimals, thousands separators and magnitude units together.
            String quantity = Pattern.quote(compact(parsedValue + parsedUnit));
            grounded &= Pattern.compile("(?<![0-9.,+−－-])" + quantity + "(?![0-9.,])")
                    .matcher(quoteKey).find();
            if (grounded && !parsedUnit.isEmpty()) {
                BigDecimal multiplier = switch (parsedUnit) {
                    case "亿元" -> BigDecimal.valueOf(100_000_000);
                    case "万元" -> BigDecimal.valueOf(10_000);
                    case "千元" -> BigDecimal.valueOf(1_000);
                    case "周", "星期" -> BigDecimal.valueOf(7);
                    case "天", "日", "元", "小时", "月", "个月", "%" -> BigDecimal.ONE;
                    case "年" -> BigDecimal.valueOf(anchored ? 12 : 1);
                    case "工作日", "自然日" -> anchored ? BigDecimal.ONE : null;
                    case "分钟", "秒", "秒钟", "美元", "欧元" -> strict ? BigDecimal.ONE : null;
                    case "万美元", "万欧元" -> strict ? BigDecimal.valueOf(10_000) : null;
                    default -> null;
                };
                if (multiplier != null) {
                    normalized = new BigDecimal(parsedValue.replace(",", "")).multiply(multiplier).stripTrailingZeros().toPlainString();
                    normalizedUnit = switch (parsedUnit) {
                        case "元", "千元", "万元", "亿元" -> currencyUnit(semantics);
                        case "周", "星期", "天", "日" -> "天";
                        case "个月" -> "月";
                        case "年" -> anchored ? "月" : "年";
                        case "万美元" -> "美元";
                        case "万欧元" -> "欧元";
                        case "秒钟" -> "秒";
                        default -> parsedUnit;
                    };
                    if (anchored) {
                        long seconds = secondsFactor(normalizedUnit);
                        if (seconds > 0) {
                            normalized = new BigDecimal(normalized).multiply(BigDecimal.valueOf(seconds)).stripTrailingZeros().toPlainString();
                            normalizedUnit = "秒";
                        }
                    }
                }
            }
        }
        String modality = modality(semantics, anchored && numeric ? parsedValue + parsedUnit : null);
        boolean fixed = numeric && semantics.contains("固定") && (modality.equals("要求") || modality.equals("陈述"))
                && !QUALIFIED.matcher(semantics).find()
                && !UPPER.matcher(semantics).find() && !LOWER.matcher(semantics).find();
        return new Fact(id, object, period, condition, metric, value, unit, normalized, normalizedUnit,
                grounded, fixed, !numeric && unit.isBlank(), modality, originalQuote);
    }

    static List<Conclusion> conclusions(List<Source> sources, List<Row> rows) {
        return conclusions(sources, rows, false);
    }

    static List<Conclusion> conclusions(List<Source> sources, List<Row> rows, boolean strict) {
        return conclusions(sources, rows, strict, false);
    }

    static List<Conclusion> conclusions(List<Source> sources, List<Row> rows, boolean strict, boolean anchored) {
        List<Conclusion> result = new ArrayList<>();
        for (Row row : rows) {
            for (int i = 0; i < sources.size(); i++) {
                Source left = sources.get(i);
                for (Fact fact : row.cells().get(i).facts()) {
                    if (!fact.verified()) continue;
                    if (fact.normalizedValue() != null) {
                        result.add(new Conclusion(Kind.KEY_DATA, row.dimension(),
                                left.fileName() + "：" + fact.quote(), List.of(fact.claimId()),
                                anchored ? ReasonCode.VERIFIED_VALUE : null));
                    }
                    for (int j = i + 1; j < sources.size(); j++) {
                        for (Fact other : row.cells().get(j).facts()) {
                            if (other.verified()) {
                                result.add(compare(row.dimension(), left, fact, sources.get(j), other, strict, anchored));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static Conclusion compare(String dimension, Source left, Fact a, Source right, Fact b,
                                      boolean strict, boolean anchored) {
        String names = left.fileName() + " 与 " + right.fileName();
        List<String> ids = List.of(a.claimId(), b.claimId());
        if (!anchored && a.textual() && b.textual() && compact(a.quote()).equals(compact(b.quote()))) {
            return new Conclusion(Kind.COMMON, dimension, names + " 均记载：" + a.quote(), ids);
        }
        ScopeGap gap = scopeGap(a, b, strict, anchored);
        if (gap != null) return new Conclusion(Kind.INCOMPARABLE, dimension, names + "：" + gap.text(), ids,
                anchored ? gap.code() : null);
        if (a.textual() && b.textual()) {
            boolean sameAction = actionKey(a, anchored).equals(actionKey(b, anchored));
            boolean opposite = (a.modality().equals("要求") && b.modality().equals("禁止"))
                    || (a.modality().equals("禁止") && b.modality().equals("要求"));
            Kind kind = sameAction && opposite ? Kind.POSSIBLE_CONFLICT
                    : sameAction && a.modality().equals(b.modality()) ? Kind.COMMON : Kind.DIFFERENT;
            ReasonCode code = switch (kind) {
                case COMMON -> ReasonCode.EQUIVALENT_OBLIGATION;
                case POSSIBLE_CONFLICT -> ReasonCode.OPPOSITE_OBLIGATION;
                default -> ReasonCode.OBLIGATION_DIFFERENCE;
            };
            return new Conclusion(kind, dimension,
                    names + " 分别记载「" + a.quote() + "」与「" + b.quote() + "」", ids, anchored ? code : null);
        }
        if (anchored && a.textual() != b.textual())
            return new Conclusion(Kind.INCOMPARABLE, dimension, names + "：数值与义务要求无法直接对比", ids, ReasonCode.TYPE_MISMATCH);
        BigDecimal secondsA = strict ? fixedDuration(a) : null;
        BigDecimal secondsB = strict ? fixedDuration(b) : null;
        boolean compareFixedDurations = secondsA != null && secondsB != null;
        if (!compareFixedDurations && (a.normalizedUnit() == null || !a.normalizedUnit().equals(b.normalizedUnit())
                || a.normalizedValue() == null || b.normalizedValue() == null)) {
            return new Conclusion(Kind.INCOMPARABLE, dimension, names + "：单位缺失或无法确定性换算", ids,
                    anchored ? ReasonCode.INCOMPATIBLE_UNIT : null);
        }
        String values = left.fileName() + " " + a.rawValue() + a.unit() + "，" + right.fileName()
                + " " + b.rawValue() + b.unit();
        BigDecimal valueA = compareFixedDurations ? secondsA : new BigDecimal(a.normalizedValue());
        BigDecimal valueB = compareFixedDurations ? secondsB : new BigDecimal(b.normalizedValue());
        boolean equal = valueA.compareTo(valueB) == 0;
        if (equal && a.modality().equals(b.modality())) {
            return new Conclusion(Kind.COMMON, dimension, values + "；换算后数值相同", ids,
                    anchored ? ReasonCode.EQUIVALENT_VALUE : null);
        }
        // Require an explicit fixed value on both sides. Limits and recommendations may both be satisfied.
        if (!equal && a.fixed() && b.fixed()) {
            return new Conclusion(Kind.POSSIBLE_CONFLICT, dimension, values + "；相同口径的固定值不一致。"
                    + names + " 分别记载「" + a.quote() + "」与「" + b.quote() + "」", ids,
                    anchored ? ReasonCode.FIXED_VALUE_CONFLICT : null);
        }
        if (!a.condition().isBlank() && (disjointBounds(a, b, valueA, valueB) || disjointBounds(b, a, valueB, valueA))) {
            return new Conclusion(Kind.POSSIBLE_CONFLICT, dimension, values + "；同一条件下的下限高于上限。"
                    + names + " 分别记载「" + a.quote() + "」与「" + b.quote() + "」", ids,
                    anchored ? ReasonCode.DISJOINT_BOUNDS : null);
        }
        return new Conclusion(Kind.DIFFERENT, dimension, values + "；原文的数值或限定语不同", ids,
                anchored ? ReasonCode.VALUE_OR_LIMIT_DIFFERENCE : null);
    }

    private static boolean disjointBounds(Fact lower, Fact upper, BigDecimal lowerValue, BigDecimal upperValue) {
        return lower.modality().equals("含下限") && upper.modality().equals("含上限")
                && lowerValue.compareTo(upperValue) > 0;
    }

    private record ScopeGap(ReasonCode code, String text) {}

    private static ScopeGap scopeGap(Fact a, Fact b, boolean strict, boolean anchored) {
        if (a.object().isBlank() || b.object().isBlank()) return new ScopeGap(ReasonCode.OBJECT_MISSING, "原文缺少明确的比较对象");
        if (!objectKey(a.object()).equals(objectKey(b.object()))) return new ScopeGap(ReasonCode.OBJECT_MISMATCH, "比较对象不同");
        if (!metricKey(a, strict, anchored).equals(metricKey(b, strict, anchored)))
            return new ScopeGap(ReasonCode.METRIC_MISMATCH, "指标口径不同（" + a.metric() + " / " + b.metric() + "）");
        if (!periodKey(a.period()).equals(periodKey(b.period())))
            return new ScopeGap(a.period().isBlank() || b.period().isBlank() ? ReasonCode.PERIOD_MISSING : ReasonCode.PERIOD_MISMATCH,
                    "期间不同或单侧缺失（" + label(a.period()) + " / " + label(b.period()) + "）");
        if (!conditionKey(a.condition(), strict).equals(conditionKey(b.condition(), strict)))
            return new ScopeGap(a.condition().isBlank() || b.condition().isBlank() ? ReasonCode.CONDITION_MISSING : ReasonCode.CONDITION_MISMATCH,
                    "适用条件不同或单侧缺失（" + label(a.condition()) + " / " + label(b.condition()) + "）");
        if (a.period().isBlank() && (monetary(a) || monetary(b))) return new ScopeGap(ReasonCode.PERIOD_MISSING, "金额对应的期间缺失");
        if (a.condition().isBlank() && (a.fixed() || b.fixed())) return new ScopeGap(ReasonCode.CONDITION_MISSING, "固定值的适用条件缺失");
        return null;
    }

    private static BigDecimal fixedDuration(Fact fact) {
        if (fact.normalizedValue() == null || fact.normalizedUnit() == null) return null;
        long seconds = secondsFactor(fact.normalizedUnit());
        return seconds == 0 ? null : new BigDecimal(fact.normalizedValue()).multiply(BigDecimal.valueOf(seconds));
    }

    private static long secondsFactor(String unit) {
        return switch (unit) {
            case "天" -> 86400;
            case "小时" -> 3600;
            case "分钟" -> 60;
            case "秒" -> 1;
            default -> 0;
        };
    }

    private static String metricKey(Fact fact, boolean strict, boolean anchored) {
        if (!strict) return ExplicitComparisonFacts.operationKey(compact(fact.metric()));
        String metric = normalizedText(fact.metric()).replaceFirst("[。.]$", "");
        String value = normalizedText(fact.rawValue() + fact.unit());
        if (!value.isEmpty() && (metric.endsWith("为" + value) || metric.endsWith("是" + value)
                || metric.endsWith(":" + value) || metric.endsWith("=" + value))) {
            metric = metric.substring(0, metric.length() - value.length() - 1);
        }
        if (anchored) return AnchoredComparisonFacts.metricKey(metric);
        return ExplicitComparisonFacts.operationKey(metric.replaceAll("校验|校正", "校准"))
                .replaceFirst("(校准|巡检|检修|检查|维护|保养)(?:间隔|周期)$", "$1")
                .replaceFirst("^年度(?=.{0,10}预算$)", "");
    }

    private static String conditionKey(String condition, boolean strict) {
        return strict ? normalizedText(condition).replaceFirst("^在", "").replaceFirst("[下时]$", "")
                : compact(condition);
    }

    private static String normalizedText(String value) {
        return compact(Normalizer.normalize(value, Normalizer.Form.NFKC)).replace('−', '-');
    }

    private static String equipmentKey(String value) {
        return Pattern.compile(EQUIPMENT_CODE, Pattern.CASE_INSENSITIVE).matcher(value)
                .replaceAll(match -> match.group().toLowerCase(Locale.ROOT));
    }

    private static String modality(String quote, String anchoredQuantity) {
        boolean upper = UPPER.matcher(quote).find();
        boolean lower = LOWER.matcher(quote).find();
        if (anchoredQuantity != null) {
            String quantity = Pattern.quote(compact(anchoredQuantity));
            String original = normalizedText(quote);
            upper |= Pattern.compile("(?:最高|不大于|不晚于|不迟于)(?:为|是)?" + quantity + "|" + quantity + "以内")
                    .matcher(original).find();
            lower |= Pattern.compile("(?:最低|不小于|不早于)(?:为|是)?" + quantity).matcher(original).find();
        }
        if (upper || lower) {
            if (upper && lower) return "区间：" + compact(quote);
            String direction = upper ? "含上限" : "含下限";
            var soft = SOFT_LIMIT.matcher(quote);
            return soft.find() ? soft.group() + direction : direction;
        }
        if (QUALIFIED.matcher(quote).find()) return QUALIFIED.matcher(quote).results().map(m -> m.group()).distinct().sorted().toList().toString();
        var words = MODAL.matcher(quote).results().map(m -> m.group()).toList();
        if (words.isEmpty()) return "陈述";
        if (words.size() > 1) return "复合限定：" + compact(quote);
        return switch (words.getFirst()) {
            case "不得", "禁止", "严禁", "不可", "不能", "不应" -> "禁止";
            case "无需", "不用", "不需要", "不必" -> "无须";
            case "允许", "可以", "可选" -> "允许";
            case "建议" -> "建议";
            default -> "要求";
        };
    }

    private static String objectKey(String object) {
        return compact(object).toLowerCase(Locale.ROOT).replaceAll("^(设备|装置|机组|仪器|泵)" + EQUIPMENT_CODE + "$", "$2$1");
    }

    private static String periodKey(String period) {
        return compact(period).replaceFirst("^(\\d{4})年度$", "$1年");
    }

    private static String actionKey(Fact fact, boolean anchored) {
        String words = compact(anchored ? fact.rawValue() : fact.quote()).replaceAll("[。.!！]$", "")
                .replace(compact(fact.object()), objectKey(fact.object()))
                .replaceAll("切断电源|切断供电|关闭电源|断开电源", "断电");
        return MODAL.matcher(words).replaceAll("");
    }

    private static String currencyUnit(String quote) {
        for (String currency : List.of("港币", "港元", "美元", "日元", "台币", "欧元", "澳元")) {
            if (quote.contains(currency)) return currency;
        }
        return "元";
    }

    private static boolean monetary(Fact fact) { return fact.unit().endsWith("元"); }
    private static String label(String value) { return value.isBlank() ? "未注明" : value; }
    private static String clean(String value) { return value == null ? "" : value.strip(); }
    private static String compact(String value) { return value.replaceAll("(?U)\\s+", ""); }
}
