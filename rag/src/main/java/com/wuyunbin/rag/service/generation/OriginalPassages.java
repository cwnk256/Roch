package com.wuyunbin.rag.service.generation;

import com.wuyunbin.rag.service.ExcelDocumentParser;
import com.wuyunbin.rag.service.PdfDocumentParser;
import com.wuyunbin.rag.service.WordDocumentParser;
import com.wuyunbin.rag.service.storage.DocumentStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Holds parsed originals only during a single worker's report generation. */
@Component
@RequiredArgsConstructor
public class OriginalPassages {
    private final DocumentStorage storage;
    private final PdfDocumentParser pdf;
    private final WordDocumentParser word;
    private final ExcelDocumentParser excel;
    private final ThreadLocal<Map<String, Map<Integer, String>>> taskSources = new ThreadLocal<>();

    public void beginTask() { taskSources.set(new HashMap<>()); }
    public void endTask() { taskSources.remove(); }

    record Match(boolean available, String quote) {}

    Match find(String id, Integer page, Pattern expression) {
        var cache = taskSources.get();
        Map<Integer, String> pages = cache == null ? parse(id) : cache.computeIfAbsent(id, this::parse);
        if (pages.isEmpty()) return new Match(false, null);
        // Plain text and Office extracts use key zero. A missing PDF page cannot match another page.
        String source = pages.containsKey(0) ? pages.get(0) : page == null ? null : pages.get(page);
        if (source == null) return new Match(true, null);
        var matcher = expression.matcher(source);
        String unique = null;
        while (matcher.find()) {
            String found = matcher.group();
            if (unique == null) unique = found;
            else if (!unique.equals(found)) return new Match(true, null);
        }
        return new Match(true, unique);
    }

    private Map<Integer, String> parse(String id) {
        try {
            var stored = storage.load(id);
            if (stored.isEmpty()) return Map.of();
            var file = stored.get();
            if (!org.springframework.util.DigestUtils.md5DigestAsHex(file.content()).equals(id)) return Map.of();
            var input = new ByteArrayInputStream(file.content());
            String name = file.fileName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".pdf")) {
                Map<Integer, String> pages = new HashMap<>();
                for (var page : pdf.parsePages(input)) pages.put(page.number(), page.content());
                return pages;
            }
            if (name.endsWith(".docx")) return Map.of(0, word.parseToMarkdown(input));
            if (name.endsWith(".xls") || name.endsWith(".xlsx")) return Map.of(0, excel.parseToMarkdown(input));
            if (name.endsWith(".txt")) return Map.of(0, new String(file.content(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unavailable) {
            return Map.of();
        }
        return Map.of();
    }
}
