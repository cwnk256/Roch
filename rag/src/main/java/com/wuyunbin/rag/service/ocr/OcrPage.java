package com.wuyunbin.rag.service.ocr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Coordinates are normalized in the displayed orientation of the original physical page. */
public record OcrPage(int page, String engineVersion, int rotation, List<Block> blocks, List<Table> tables) {
    public OcrPage(int page, String engineVersion, List<Block> blocks, List<Table> tables) {
        this(page, engineVersion, 0, blocks, tables);
    }

    public record Block(String text, List<Double> box, double confidence) {}
    public record Table(List<Double> box, List<Cell> cells) {}
    public record Cell(int row, int column, int rowSpan, int columnSpan, String text, List<Double> box, double confidence) {}
    public record TableFact(String object, List<String> headers, String amount) {
        public TableFact { headers = List.copyOf(headers); }

        public String metric() { return String.join(" ", headers); }
        public String statement() { return object + "的" + metric() + "为" + amount; }
        public String comparisonStatement() { return "对象：" + object + "；指标：" + metric() + "；原值：" + amount; }

        public String canonicalAnswer(String proposed) {
            String answer = compactAnswer(proposed).replaceFirst("[。.]$", "");
            for (String indicator : List.of(metric(), String.join("的", headers))) {
                for (String connector : List.of("为", "是", "：", ":")) {
                    if (answer.equals(compactAnswer(object + "的" + indicator + connector + amount))) return statement();
                }
            }
            return null;
        }

        public boolean answersQuestion(String question) {
            String text = QUESTION_PREFIX.matcher(compactAnswer(question).replaceFirst("[？?。]$", "")).replaceFirst("");
            String leaf = headers.getLast();
            String metricWithoutUnit = leaf.replaceFirst("[（(](?:" + MEASURES + ")[）)]$", "");
            for (String indicator : List.of(metric(), String.join("的", headers), leaf, metricWithoutUnit)) {
                for (String connector : List.of("的", "")) {
                    String subject = compactAnswer(object + connector + indicator);
                    if (!text.startsWith(subject)) continue;
                    var requested = AMOUNT_QUESTION.matcher(text.substring(subject.length()));
                    if (!requested.matches()) continue;
                    String unit = requested.group("unit");
                    if (unit == null) return true;
                    var valueUnit = VALUE_UNIT.matcher(compactAnswer(amount));
                    if (valueUnit.matches() && !valueUnit.group(1).isEmpty()) {
                        if (unit.equals(valueUnit.group(1))) return true;
                    } else {
                        var headerUnit = HEADER_UNIT.matcher(compactAnswer(leaf));
                        if (headerUnit.find() && unit.equals(headerUnit.group(1))) return true;
                    }
                }
            }
            return false;
        }

        private static String compactAnswer(String phrase) {
            return WHITE.matcher(phrase).replaceAll("");
        }
    }

    public record Anchor(String quote, List<Double> box, Integer row, Integer column, boolean table,
                         boolean reviewRequired, TableFact tableFact, String context) {
        public String fact() { return tableFact == null ? null : tableFact.statement(); }
    }
    static final double MIN_CONFIDENCE = .85;
    private static final String MEASURES =
            "亿元|万元|千元|元|美元|欧元|工作日|自然日|小时|分钟|秒|天|周|月|年|万亩|亩|平方公里|平方米|千克|公斤|毫克|克|公里|毫米|米|升|毫升|分|kW|mW|MW|W|%|％";
    private static final Pattern VALUE_UNIT = Pattern.compile("^[+−-]?[0-9,]+(?:\\.[0-9]+)?(.*)$");
    private static final Pattern HEADER_UNIT = Pattern.compile("[（(]([^（）()]+)[）)]$");
    private static final Pattern QUESTION_PREFIX = Pattern.compile(
            "^(?:(?:请)?(?:问|核对|说明|给出|列出)|(?:根据|查看|在)?(?:本表|该表|表\\d+(?:[-.]\\d+)*)(?:中|里)?[，,：:]?)+");
    private static final Pattern AMOUNT_QUESTION = Pattern.compile(
            "^(?:(?:为|是)?(?:多少|何值|几|什么)(?:(?<unit>" + MEASURES + ")|名|位)?|(?:排|列|位列)?第几[名位]?)?$");
    private static final Pattern WHITE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public OcrPage {
        if (page < 0 || engineVersion == null || engineVersion.isBlank() || engineVersion.length() > 200
                || rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270
                || blocks == null || tables == null || blocks.size() > 10_000 || tables.size() > 500) {
            throw new IllegalArgumentException("OCR 页面结果不完整");
        }
        blocks = blocks.stream().map(block -> new Block(clean(block.text()), checkedBox(block.box()),
                checkedConfidence(block.confidence()))).toList();
        tables = tables.stream().map(OcrPage::validatedTable).toList();
    }

    private static Table validatedTable(Table table) {
        if (table.cells() == null || table.cells().size() > 10_000) {
            throw new IllegalArgumentException("OCR 表格结果不完整");
        }
        return new Table(checkedBox(table.box()), table.cells().stream().map(cell -> new Cell(cell.row(),
                cell.column(), cell.rowSpan(), cell.columnSpan(), clean(cell.text()), checkedBox(cell.box()),
                checkedConfidence(cell.confidence()))).toList());
    }

    public List<Anchor> anchors() {
        List<Anchor> anchors = new ArrayList<>();
        for (Block block : blocks) {
            if (block.text().isBlank() || tables.stream().anyMatch(table -> overlapFraction(block.box(), table.box()) >= .30)) continue;
            var sentences = block.text().split("(?<=[。！？；!?;])");
            for (String sentence : sentences) {
                if (!sentence.isBlank()) anchors.add(new Anchor(sentence.strip(), block.box(), null, null, false,
                        block.confidence() < MIN_CONFIDENCE, null, block.text()));
            }
        }
        for (Table table : tables) {
            boolean continuation = blocks.stream().anyMatch(block -> OcrTableEvidence.isContinuation(block, table, rotation));
            boolean conflicting = false;
            Map<Cell, List<Block>> byCell = new LinkedHashMap<>();
            for (Block block : blocks) {
                if (block.text().isBlank() || overlapFraction(block.box(), table.box()) < .30) continue;
                var matchingCells = table.cells().stream()
                        .filter(cell -> overlapFraction(block.box(), cell.box()) >= .20).toList();
                if (matchingCells.size() == 1 && overlapFraction(block.box(), matchingCells.getFirst().box()) >= .70) {
                    byCell.computeIfAbsent(matchingCells.getFirst(), ignored -> new ArrayList<>()).add(block);
                } else {
                    conflicting |= !matchingCells.isEmpty();
                    anchors.add(new Anchor(block.text(), block.box(), null, null, true, true, null, block.text()));
                }
            }
            for (var entry : byCell.entrySet()) {
                var recognized = entry.getValue().stream()
                        .sorted(java.util.Comparator.comparingDouble((Block block) -> uprightBox(block.box(), rotation).get(1))
                                .thenComparingDouble(block -> uprightBox(block.box(), rotation).get(0)))
                        .map(Block::text).toList();
                if (!withoutSpace(String.join("", recognized)).equals(withoutSpace(entry.getKey().text()))) {
                    conflicting = true;
                    for (Block block : entry.getValue()) {
                        anchors.add(new Anchor(block.text(), block.box(), null, null, true, true, null, block.text()));
                    }
                }
            }
            anchors.addAll(OcrTableEvidence.anchors(table, continuation || conflicting, rotation));
        }
        Map<String, List<Anchor>> grouped = new LinkedHashMap<>();
        for (Anchor anchor : anchors) grouped.computeIfAbsent(anchor.quote(), ignored -> new ArrayList<>()).add(anchor);
        // A repeated quote cannot select one physical region or data cell with certainty.
        return grouped.values().stream().map(items -> {
            Anchor first = items.getFirst();
            return items.size() == 1 ? first : new Anchor(first.quote(), null, null, null,
                    items.stream().anyMatch(Anchor::table), true, null, first.context());
        }).toList();
    }

    public String indexedText() {
        return String.join("\n\n", anchors().stream().map(Anchor::quote).toList());
    }

    private static String withoutSpace(String text) {
        return WHITE.matcher(text).replaceAll("");
    }

    private static String clean(String text) {
        String cleaned = WHITE.matcher(Objects.requireNonNullElse(text, "").replace("\0", "")).replaceAll(" ").strip();
        if (cleaned.length() > 20_000) throw new IllegalArgumentException("OCR 摘录超过页面限制");
        return cleaned;
    }

    private static double checkedConfidence(double confidence) {
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("OCR 置信度超出范围");
        }
        return confidence;
    }

    private static List<Double> checkedBox(List<Double> box) {
        if (box == null || box.size() != 4 || box.stream().anyMatch(
                v -> v == null || !Double.isFinite(v) || v < 0 || v > 1) ||
                box.get(0) >= box.get(2) || box.get(1) >= box.get(3)) {
            throw new IllegalArgumentException("OCR 坐标超出原页范围");
        }
        return List.copyOf(box);
    }

    static double overlapFraction(List<Double> subject, List<Double> area) {
        double width = Math.max(0, Math.min(subject.get(2), area.get(2)) - Math.max(subject.get(0), area.get(0)));
        double height = Math.max(0, Math.min(subject.get(3), area.get(3)) - Math.max(subject.get(1), area.get(1)));
        return width * height / boxArea(subject);
    }

    static List<Double> uprightBox(List<Double> box, int rotation) {
        double left = box.get(0), top = box.get(1), right = box.get(2), bottom = box.get(3);
        return switch (rotation) {
            case 90 -> List.of(top, 1 - right, bottom, 1 - left);
            case 180 -> List.of(1 - right, 1 - bottom, 1 - left, 1 - top);
            case 270 -> List.of(1 - bottom, left, 1 - top, right);
            default -> box;
        };
    }

    static double boxArea(List<Double> box) {
        return (box.get(2) - box.get(0)) * (box.get(3) - box.get(1));
    }
}
