package com.wuyunbin.rag.service.generation;

/** Offsets are UTF-16 positions in the native extraction. Ignore layout whitespace only. */
record NativeTextSpan(int start, int end, String quote) {
    static NativeTextSpan find(String source, String proposed) {
        if (source == null || proposed == null || proposed.isBlank()) return null;
        StringBuilder normalized = new StringBuilder(source.length());
        int[] offsets = new int[source.length()];
        for (int i=0; i<source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            offsets[normalized.length()] = i;
            normalized.append(Character.toLowerCase(c));
        }
        String needle = proposed.replaceAll("(?U)\\s+", "").toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) return null;
        int from = 0;
        NativeTextSpan match = null;
        while (from < normalized.length()) {
            int index = normalized.indexOf(needle, from);
            if (index < 0) break;
            int start = offsets[index], end = offsets[index + needle.length() - 1] + 1;
            from = index + 1;
            if (splitsNumber(source, start, end)) continue;
            String quote = source.substring(start, end);
            if (match != null) return null;
            match = new NativeTextSpan(start, end, quote);
        }
        return match;
    }

    boolean isCompleteClause(String source) { return isCompleteClause(source, false); }

    boolean isCompleteClause(String source, boolean allowParagraphLines) {
        String before = source.substring(0, start).stripTrailing();
        String after = source.substring(end).stripLeading();
        boolean left = before.isEmpty() || terminal(before.charAt(before.length()-1))
                || allowParagraphLines && source.substring(before.length(), start).contains("\n");
        boolean right = after.isEmpty() || terminal(quote.charAt(quote.length()-1)) || terminal(after.charAt(0))
                || allowParagraphLines && source.substring(end, source.length()-after.length()).contains("\n");
        return left && right;
    }

    private static boolean terminal(char c) { return "。！？!?；;.".indexOf(c) >= 0; }

    private static boolean splitsNumber(String source, int start, int end) {
        if (Character.isDigit(source.charAt(start)) && start > 0) {
            char previous = source.charAt(start-1);
            if (Character.isDigit(previous) || "+−-".indexOf(previous) >= 0
                    || ",.".indexOf(previous) >= 0 && start > 1 && Character.isDigit(source.charAt(start-2))) return true;
        }
        if (!Character.isDigit(source.charAt(end-1)) || end >= source.length()) return false;
        char next = source.charAt(end);
        return Character.isDigit(next) || ",.".indexOf(next) >= 0 && end+1 < source.length()
                && Character.isDigit(source.charAt(end+1));
    }
}