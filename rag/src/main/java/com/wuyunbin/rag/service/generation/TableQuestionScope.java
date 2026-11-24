package com.wuyunbin.rag.service.generation;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Table identifiers in one question constrain that question's citations independently. */
final class TableQuestionScope {
    private static final Pattern QUESTION = Pattern.compile("^\\s*(\\d{1,5})\\s*[.、)）]\\s*(.*)$");
    private static final Pattern TABLE = Pattern.compile("(?i)(?:表|(?<![A-Za-z])table)\\s*([0-9]+(?:[-.]\\d+)*)");
    private final Map<Integer,Set<String>> numbered = new HashMap<>();
    private final Set<String> shared = new HashSet<>();

    TableQuestionScope(String purpose) {
        Integer current = null;
        for (String line : Objects.toString(purpose, "").lines().toList()) {
            String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC);
            var item = QUESTION.matcher(normalized);
            if (item.matches()) {
                normalized = item.group(2);
                current = Integer.parseInt(item.group(1));
                numbered.computeIfAbsent(current, key -> new HashSet<>());
            }
            var ids = current == null ? shared : numbered.get(current);
            var table = TABLE.matcher(normalized);
            while (table.find()) ids.add(table.group(1));
        }
    }

    boolean allows(Integer number, ClaimVerifier.Citation citation) {
        if (citation == null) return false;
        var ids = numbered.isEmpty() ? shared : number == null ? null : numbered.get(number);
        if (ids == null) return false;
        if (ids.isEmpty()) ids = shared;
        if (ids.isEmpty()) return true;
        if (citation.physicalFacts() == null || citation.tableCaption() == null) return false;
        String id = captionId(citation.tableCaption());
        return id != null && ids.contains(id);
    }

    static String captionId(String title) {
        if (title == null) return null;
        var caption = TABLE.matcher(Normalizer.normalize(title.strip(), Normalizer.Form.NFKC));
        return caption.lookingAt() ? caption.group(1) : null;
    }
}