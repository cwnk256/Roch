package com.wuyunbin.rag.service.comparison;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.wuyunbin.rag.service.comparison.ComparisonResult.*;

class ComparisonRulesTest {
    private final List<Source> sources = List.of(new Source("A", "A.pdf"), new Source("B", "B.md"), new Source("C", "C.txt"));

    private Fact fact(String id, String object, String period, String condition, String metric,
                      String value, String unit, String quote) {
        return ComparisonRules.fact(id, new ComparisonCandidate(1, quote, object, period, condition,
                metric, value, unit), true);
    }

    private Conclusion compare(Fact left, Fact right) {
        return compare(left, right, false);
    }

    private Conclusion compare(Fact left, Fact right, boolean strict) {
        Row row = new Row("比较", List.of(new Cell("A", Status.FOUND, "", List.of(left)),
                new Cell("B", Status.FOUND, "", List.of(right)),
                new Cell("C", Status.NOT_FOUND, "本次未找到依据", List.of())));
        return ComparisonRules.conclusions(sources, List.of(row), strict).stream()
                .filter(c -> c.claimIds().size() > 1).findFirst().orElseThrow();
    }

    @Test void sameBudgetAfterDeterministicConversionNamesBothParticipatingDocumentsOnly() {
        Fact a = fact("a", "甲项目", "2025年", "", "预算", "100", "万元", "甲项目2025年预算100万元。");
        Fact b = fact("b", "甲项目", "2025年", "", "预算", "1,000,000", "元", "甲项目2025年预算1,000,000元。");
        assertEquals("1000000", a.normalizedValue());
        Conclusion result = compare(a, b);
        assertEquals(Kind.COMMON, result.kind());
        assertEquals(List.of("a", "b"), result.claimIds());
        assertTrue(result.text().contains("A.pdf") && result.text().contains("B.md"));
        assertFalse(result.text().contains("C.txt"));
    }

    @Test void differentPeriodsAndMissingObjectNeverBecomeConflicts() {
        Fact a = fact("a", "甲项目", "2025年", "", "预算", "100", "万元", "甲项目2025年预算100万元。");
        Fact b = fact("b", "甲项目", "2025年上半年", "", "预算", "60", "万元", "甲项目2025年上半年预算60万元。");
        assertEquals(Kind.INCOMPARABLE, compare(a, b).kind());
        assertEquals(Kind.INCOMPARABLE, compare(a, fact("c", "", "2025年", "", "预算", "60", "万元", "2025年预算60万元。")).kind());
    }

    @Test void fixedCalibrationPeriodsConflictButLowerBoundsDoNot() {
        Fact a = fact("a", "设备甲", "", "常规环境", "校准", "30", "天", "设备甲在常规环境的固定校准周期为30天。");
        Fact b = fact("b", "设备甲", "", "常规环境", "校准", "60", "天", "设备甲在常规环境的固定校准周期为60天。");
        assertEquals(Kind.POSSIBLE_CONFLICT, compare(a, b).kind());
        Fact lower = fact("b", "设备甲", "", "常规环境", "校准", "60", "天", "设备甲在常规环境的校准周期至少60天。");
        assertNotEquals(Kind.POSSIBLE_CONFLICT, compare(a, lower).kind());
    }

    @Test void modelCannotInventFieldsOrExtractAPositiveValueFromANegativeValue() {
        assertFalse(fact("a", "设备乙", "", "", "预算", "100", "元", "设备甲预算100元。").verified());
        assertFalse(fact("a", "设备甲", "", "", "预算", "100", "元", "设备甲预算-100元。").verified());
        assertFalse(fact("a", "设备甲", "", "", "预算", "100", "元", "设备甲预算2100元。").verified());
    }
    @Test void variableIntervalsAndDisjunctionsCannotBeDeclaredFixed() {
        Fact fixed = fact("a", "设备甲", "", "常规环境", "校准", "30", "天", "设备甲在常规环境固定每30天校准。");
        for (String quote : List.of("设备甲在常规环境的校准周期不固定，通常60天。",
                "设备甲在常规环境固定每40天或每60天校准。", "设备甲在常规环境的固定校准周期为30—60天。")) {
            Fact alternative = fact("b", "设备甲", "", "常规环境", "校准", "60", "天", quote);
            assertNotEquals(Kind.POSSIBLE_CONFLICT, compare(fixed, alternative).kind(), quote);
        }
    }

    @Test void sharedSubstringDoesNotEraseOppositeSafetyRequirements() {
        Fact positive = fact("a", "甲泵", "", "", "检修", "断电后检修", "", "甲泵必须断电后检修。");
        Fact negative = fact("b", "甲泵", "", "", "检修", "断电后检修", "", "甲泵不得断电后检修。");
        assertEquals(Kind.POSSIBLE_CONFLICT, compare(positive, negative).kind());
        Fact optional = fact("b", "甲泵", "", "", "检修", "断电后检修", "", "甲泵无需断电后检修。");
        assertNotEquals(Kind.POSSIBLE_CONFLICT, compare(positive, optional).kind());
    }

    @Test void upperAndLowerBoundsAreDistinctEvenWithSameNumberOrNegativePrefix() {
        Fact upper = fact("a", "设备甲", "", "常温", "校准", "20", "天", "设备甲常温校准间隔不得超过20天。");
        Fact lower = fact("b", "设备甲", "", "常温", "校准", "20", "天", "设备甲常温校准间隔不得低于20天。");
        assertEquals(Kind.DIFFERENT, compare(upper, lower).kind());
        Fact upperAlias = fact("b", "设备甲", "", "常温", "校准", "20", "天", "设备甲常温校准间隔至多20天。");
        assertEquals(Kind.COMMON, compare(upper, upperAlias).kind());
    }

    @Test void matchingValueFragmentsDoNotOverrideDifferentQualifiedActions() {
        Fact immediate = fact("a", "甲泵", "", "", "检修", "断电", "", "甲泵检修前必须立即断电。");
        Fact delayed = fact("b", "甲泵", "", "", "检修", "断电", "", "甲泵检修前必须延时断电。");
        assertEquals(Kind.DIFFERENT, compare(immediate, delayed).kind());
    }

    @Test void disjointBoundsConflictOnlyWhenObjectAndConditionsMatch() {
        Fact lower = fact("a", "设备甲", "", "常温", "校准", "7", "天", "常温下设备甲校准周期至少7天。");
        Fact upper = fact("b", "设备甲", "", "常温", "校准", "5", "天", "常温下设备甲校准周期至多5天。");
        assertEquals(Kind.POSSIBLE_CONFLICT, compare(lower, upper).kind());
        Fact otherCondition = fact("b", "设备甲", "", "高温", "校准", "5", "天", "高温下设备甲校准周期至多5天。");
        assertEquals(Kind.INCOMPARABLE, compare(lower, otherCondition).kind());
        Fact recommendation = fact("b", "设备甲", "", "常温", "校准", "5", "天", "常温下设备甲校准周期建议至多5天。");
        assertEquals(Kind.DIFFERENT, compare(lower, recommendation).kind());
    }

    @Test void alignsMetricSuffixEquipmentCaseAndSameDurationUnits() {
        String hoursSource = "K17设备响应耗时为3小时。";
        String minutesSource = "k17设备响应耗时为180分钟。";
        Fact hours = ComparisonRules.fact("a", new ComparisonCandidate(1, hoursSource, "k17设备", "",
                "", "响应耗时为3小时", "3", "小时"), true, hoursSource, true);
        Fact minutes = ComparisonRules.fact("b", new ComparisonCandidate(1, minutesSource, "K17设备", "",
                "", "响应耗时", "180", "分钟"), true, minutesSource, true);
        assertEquals(Kind.COMMON, compare(hours, minutes, true).kind());
    }
}
