package com.wuyunbin.rag.service.ocr;

import com.wuyunbin.rag.service.ocr.OcrPage.Anchor;
import com.wuyunbin.rag.service.ocr.OcrPage.Cell;
import com.wuyunbin.rag.service.ocr.OcrPage.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class OcrTableEvidence {
    private static final int MAX_GRID_SIZE = 256;
    private static final Pattern NUMBER = Pattern.compile(
            "^[+−-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?\\s*(?:[%％]|[\\p{L}℃°]+)?$");
    private static final Pattern YEAR_HEADER = Pattern.compile("^[\\p{IsHan}]{0,8}(?:年|年度|年份|期间|日期|时间)(?:[（(]年[）)])?$");
    private static final Pattern YEAR = Pattern.compile("^[12]\\d{3}\\s*年?$");
    private static final Pattern CONTINUATION = Pattern.compile("续表|表\\s*\\S*\\s*[（(]续[）)]|(?i:\\bcontinued\\b)");

    private OcrTableEvidence() {}

    static boolean isContinuation(OcrPage.Block block, Table table, int rotation) {
        if (!CONTINUATION.matcher(block.text()).find()) return false;
        var text = OcrPage.uprightBox(block.box(), rotation);
        var area = OcrPage.uprightBox(table.box(), rotation);
        double gap = area.get(1) - text.get(3);
        return gap >= -.02 && gap <= .04 && text.get(0) < area.get(2) && text.get(2) > area.get(0);
    }

    static List<Anchor> anchors(Table table, boolean continuation, int rotation) {
        List<Cell> cells = table.cells().stream()
                .sorted(Comparator.comparingInt(Cell::row).thenComparingInt(Cell::column)).toList();
        Map<Slot, Cell> grid = grid(table, cells, rotation);
        int headerRows = cells.stream().filter(cell -> cell.column() > 0 && numeric(cell.text()))
                .filter(cell -> !yearHeaderRow(cell, grid))
                .mapToInt(Cell::row).min().orElse(0);
        boolean usable = !continuation && !grid.isEmpty() && headerRows > 0 && cells.stream()
                .noneMatch(cell -> CONTINUATION.matcher(cell.text()).find());
        List<Anchor> result = new ArrayList<>();
        for (Cell value : cells) {
            if (value.text().isBlank()) continue;
            Anchor anchor = usable && value.row() >= headerRows && value.column() > 0 && numeric(value.text())
                    ? bind(value, grid, headerRows, rotation) : null;
            if (anchor != null) {
                result.add(anchor);
            } else {
                boolean positionKnown = grid.get(new Slot(value.row(), value.column())) == value;
                result.add(new Anchor(value.text(), value.box(), positionKnown ? value.row() + 1 : null,
                        positionKnown ? value.column() + 1 : null, true, true, null, value.text()));
            }
        }
        return result;
    }

    private static boolean yearHeaderRow(Cell cell, Map<Slot, Cell> grid) {
        var label = grid.get(new Slot(cell.row(), 0));
        return label != null && YEAR_HEADER.matcher(label.text()).matches() && YEAR.matcher(cell.text()).matches();
    }

    private static Anchor bind(Cell value, Map<Slot, Cell> grid, int headerRows, int rotation) {
        Cell label = grid.get(new Slot(value.row(), 0));
        if (label == null || label.text().isBlank() || numeric(label.text()) || label.row() != value.row()
                || label.rowSpan() != 1 || value.rowSpan() != 1 || value.columnSpan() != 1
                || !usable(label) || !usable(value) || !aligned(label, value, 1, rotation)) return null;
        var headers = new LinkedHashSet<Cell>();
        for (int row = 0; row < headerRows; row++) {
            Cell header = grid.get(new Slot(row, value.column()));
            if (header == null || header.column() == 0 || header.text().isBlank() || !usable(header) ||
                    header.row() + header.rowSpan() > headerRows || !aligned(header, value, 0, rotation)) return null;
            headers.add(header);
        }
        if (headers.isEmpty() || headers.getLast().columnSpan() != 1) return null;
        var fact = new OcrPage.TableFact(label.text(), headers.stream().map(Cell::text).toList(), value.text());
        String quote = label.text() + " | " + fact.metric() + " | " + value.text();
        if (quote.length() > 1000) return null;
        return new Anchor(quote, value.box(), value.row() + 1, value.column() + 1, true, false, fact, quote);
    }

    private static Map<Slot, Cell> grid(Table table, List<Cell> cells, int rotation) {
        Map<Slot, Cell> grid = new HashMap<>();
        for (Cell cell : cells) {
            if (cell.row() < 0 || cell.column() < 0 || cell.rowSpan() < 1 || cell.columnSpan() < 1
                    || cell.row() >= MAX_GRID_SIZE || cell.column() >= MAX_GRID_SIZE
                    || cell.rowSpan() > MAX_GRID_SIZE - cell.row() || cell.columnSpan() > MAX_GRID_SIZE - cell.column()
                    || OcrPage.overlapFraction(cell.box(), table.box()) < .85) return Map.of();
            for (int row = cell.row(); row < cell.row() + cell.rowSpan(); row++) {
                for (int column = cell.column(); column < cell.column() + cell.columnSpan(); column++) {
                    if (grid.putIfAbsent(new Slot(row, column), cell) != null) return Map.of();
                }
            }
        }
        for (var entry : grid.entrySet()) {
            var slot = entry.getKey();
            Cell cell = entry.getValue();
            Cell nextColumn = grid.get(new Slot(slot.row(), slot.column() + 1));
            Cell nextRow = grid.get(new Slot(slot.row() + 1, slot.column()));
            if (outOfOrder(cell, nextColumn, 0, rotation) || outOfOrder(cell, nextRow, 1, rotation)) return Map.of();
        }
        return grid;
    }

    private static boolean outOfOrder(Cell current, Cell next, int axis, int rotation) {
        if (next == null || next == current) return false;
        var first = OcrPage.uprightBox(current.box(), rotation);
        var second = OcrPage.uprightBox(next.box(), rotation);
        return midpoint(first, axis) >= midpoint(second, axis)
                || end(first, axis) - start(second, axis) > .30 * Math.min(length(first, axis), length(second, axis));
    }

    private static boolean aligned(Cell first, Cell second, int axis, int rotation) {
        var left = OcrPage.uprightBox(first.box(), rotation);
        var right = OcrPage.uprightBox(second.box(), rotation);
        double overlap = Math.min(end(left, axis), end(right, axis)) - Math.max(start(left, axis), start(right, axis));
        return overlap >= .35 * Math.min(length(left, axis), length(right, axis));
    }

    private static double midpoint(List<Double> box, int axis) { return (start(box, axis) + end(box, axis)) / 2; }
    private static double length(List<Double> box, int axis) { return end(box, axis) - start(box, axis); }
    private static double start(List<Double> box, int axis) { return box.get(axis); }
    private static double end(List<Double> box, int axis) { return box.get(axis + 2); }
    private static boolean usable(Cell cell) { return cell.confidence() >= OcrPage.MIN_CONFIDENCE; }
    private static boolean numeric(String value) { return NUMBER.matcher(value).matches(); }

    private record Slot(int row, int column) {}
}
