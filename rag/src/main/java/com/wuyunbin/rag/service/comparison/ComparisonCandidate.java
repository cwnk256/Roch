package com.wuyunbin.rag.service.comparison;

import java.util.Objects;
import java.util.StringJoiner;

/** The extractor copies each field from the quotation. Empty strings represent unknown scope. */
public record ComparisonCandidate(int source, String quote, String object, String period,
                                  String condition, String metric, String rawValue, String unit) {
    /** Judge the extracted meaning against its context, including the relationship between each field. */
    String statement() {
        StringJoiner fields = new StringJoiner("；");
        add(fields, "对象", object);
        add(fields, "期间", period);
        add(fields, "适用条件", condition);
        add(fields, "指标", metric);
        add(fields, "原值", Objects.toString(rawValue, "") + Objects.toString(unit, ""));
        return fields.length() == 0 ? "未提取到可判断的字段。" : fields.toString();
    }

    private static void add(StringJoiner output, String label, String value) {
        if (value != null && !value.isBlank()) output.add(label + "：" + value.strip());
    }
}
