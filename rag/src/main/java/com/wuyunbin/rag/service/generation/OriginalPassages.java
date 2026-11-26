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

    private final ThreadLocal<Map<String, java.util.List<ExcelDocumentParser.NativeRow>>> taskSheets = new ThreadLocal<>();
    private final ThreadLocal<Map<String, PdfDocumentParser.NativeLayout>> taskTables = new ThreadLocal<>();
    private final ThreadLocal<Map<String,String>> taskNativeTexts = new ThreadLocal<>();
    public void beginTask() { taskSources.set(new HashMap<>()); taskSheets.set(new HashMap<>()); taskTables.set(new HashMap<>()); taskNativeTexts.set(new HashMap<>()); }
    public void endTask() { taskSources.remove(); taskSheets.remove(); taskTables.remove(); taskNativeTexts.remove(); }

    record Match(boolean available, String quote, String context) {}

    Match find(String id, Integer page, Pattern expression) {
        var cache = taskSources.get();
        Map<Integer, String> pages = cache == null ? parse(id) : cache.computeIfAbsent(id, this::parse);
        if (pages.isEmpty()) return new Match(false, null, null);
        // Plain text and Office extracts use key zero. A missing PDF page cannot match another page.
        String source = pages.containsKey(0) ? pages.get(0) : page == null ? null : pages.get(page);
        if (source == null) return new Match(true, null, null);
        var matcher = expression.matcher(source);
        String unique = null;
        while (matcher.find()) {
            String found = matcher.group();
            if (unique == null) unique = found;
            else if (!unique.equals(found)) return new Match(true, null, null);
        }
        return new Match(true, unique, unique == null ? null : source);
    }

    java.util.List<PdfDocumentParser.TableRow> tableRows(String id) {
        return layout(id).rows();
    }

    String nativePdfPage(String id, Integer page) {
        return page == null ? null : layout(id).pages().get(page);
    }

    boolean hasInvisiblePdfText(String id, Integer page) {
        return page != null && layout(id).invisibleTextPages().contains(page);
    }

    private PdfDocumentParser.NativeLayout layout(String id) {
        var cache = taskTables.get();
        return cache == null ? loadTables(id) : cache.computeIfAbsent(id, this::loadTables);
    }

    private PdfDocumentParser.NativeLayout loadTables(String id) {
        try {
            var stored = storage.load(id);
            if (stored.isPresent() && org.springframework.util.DigestUtils.md5DigestAsHex(stored.get().content()).equals(id)) {
                return pdf.nativeLayout(new ByteArrayInputStream(stored.get().content()));
            }
        } catch (IOException | RuntimeException unavailable) {
            return new PdfDocumentParser.NativeLayout(Map.of(), java.util.List.of(), java.util.Set.of());
        }
        return new PdfDocumentParser.NativeLayout(Map.of(), java.util.List.of(), java.util.Set.of());
    }
    String nativeOfficeOrText(String id) {
        var cache = taskNativeTexts.get();
        return cache == null ? loadNativeText(id) : cache.computeIfAbsent(id, this::loadNativeText);
    }

    private String loadNativeText(String id) {
        try {
            var file = storage.load(id).orElseThrow();
            if (!org.springframework.util.DigestUtils.md5DigestAsHex(file.content()).equals(id)) return "";
            String name = file.fileName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".txt")) return new String(file.content(), StandardCharsets.UTF_8);
            if (name.endsWith(".docx")) {
                try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(file.content()));
                     var text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
                    return text.getText();
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            return "";
        }
        return "";
    }

    java.util.List<ExcelDocumentParser.NativeRow> rows(String id) {
        var cache = taskSheets.get();
        return cache == null ? loadRows(id) : cache.computeIfAbsent(id, this::loadRows);
    }

    private java.util.List<ExcelDocumentParser.NativeRow> loadRows(String id) {
        try {
            var stored = storage.load(id);
            if (stored.isPresent() && org.springframework.util.DigestUtils.md5DigestAsHex(stored.get().content()).equals(id)) {
                return excel.nativeRows(new ByteArrayInputStream(stored.get().content()));
            }
        } catch (IOException | RuntimeException unavailable) {
            return java.util.List.of();
        }
        return java.util.List.of();
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
