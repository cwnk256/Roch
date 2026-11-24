package com.wuyunbin.rag.service;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Independent of the indexed text stream. Coordinates are in rotation-adjusted page space. */
final class PdfTableLayout extends PDFTextStripper {
    private record Cell(String text, float left, float right) {
        float center() { return (left + right) / 2; }
        boolean aligns(Cell other, float radius) {
            return Math.abs(center() - other.center()) < radius || Math.abs(left - other.left) < 2;
        }
    }
    private record Line(float y, List<Cell> cells) {}
    private static final Pattern VALUE = Pattern.compile("[+−-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?%?|[-—–]");
    private static final Pattern SCORE = Pattern.compile("得分|分数|score", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANK = Pattern.compile("排名|名次|rank", Pattern.CASE_INSENSITIVE);
    private final List<TextPosition> glyphs = new ArrayList<>();
    final Map<Integer,String> pageTexts = new HashMap<>();
    private final StringBuilder nativePage = new StringBuilder();
    final List<PdfDocumentParser.TableRow> rows = new ArrayList<>();

    PdfTableLayout() throws IOException { setSortByPosition(false); }

    @Override protected void writeString(String text, List<TextPosition> positions) {
        nativePage.append(text.replace("\0", ""));
        for (var position : positions) if (position.getDir() == 0) glyphs.add(position);
    }

    @Override protected void writeWordSeparator() { nativePage.append(' '); }
    @Override protected void writeLineSeparator() { nativePage.append('\n'); }
    @Override protected void writeParagraphSeparator() { nativePage.append('\n'); }

    @Override protected void endPage(PDPage page) throws IOException {
        super.endPage(page);
        glyphs.sort(Comparator.comparingDouble(TextPosition::getYDirAdj));
        var lines = new ArrayList<Line>();
        for (int start = 0; start < glyphs.size();) {
            float y = glyphs.get(start).getYDirAdj();
            int end = start + 1;
            while (end < glyphs.size() && glyphs.get(end).getYDirAdj() - y < 2.0f) end++;
            var cells = splitCells(glyphs.subList(start, end));
            if (!cells.isEmpty()) lines.add(new Line(y, cells));
            start = end;
        }
        collectRows(lines, getCurrentPageNo());
        glyphs.clear();
        pageTexts.put(getCurrentPageNo(), nativePage.toString());
        nativePage.setLength(0);
    }

    private static List<Cell> splitCells(List<TextPosition> glyphs) {
        glyphs.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));
        List<Cell> cells = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        float left = 0, right = 0;
        for (var glyph : glyphs) {
            String value = Objects.toString(glyph.getUnicode(), "").replace("\0", "");
            if (value.isBlank()) {
                if (!text.isEmpty() && text.charAt(text.length()-1) != ' ') text.append(' ');
                continue;
            }
            float x = glyph.getXDirAdj();
            if (!text.isEmpty() && x - right > Math.max(2.0, glyph.getFontSizeInPt() * .30)) {
                cells.add(new Cell(text.toString().strip(), left, right));
                text.setLength(0);
            }
            if (text.isEmpty()) left = x;
            text.append(value);
            right = x + glyph.getWidthDirAdj();
        }
        if (!text.isEmpty()) cells.add(new Cell(text.toString().strip(), left, right));
        return cells;
    }

    private void collectRows(List<Line> lines, int page) {
        List<Cell> headers = List.of(), axes = List.of();
        String caption = "";
        boolean previousWasData = false;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var cells = line.cells();
            var label = cells.getFirst().text();
            if (i == 0 || line.y() - lines.get(i-1).y() > 45) { headers = List.of(); axes = List.of(); caption = ""; }
            boolean data = cells.size() >= 3 && !VALUE.matcher(label).matches()
                    && cells.subList(1, cells.size()).stream().allMatch(c -> VALUE.matcher(c.text()).matches());
            if (!data) {
                if (previousWasData) caption = "";
                previousWasData = false;
                if (label.matches("(?i)^(?:表|table)\\s*\\d+.*")) {
                    caption = cells.stream().map(Cell::text).collect(java.util.stream.Collectors.joining(" "));
                }
                axes = headers;
                // A vertically centered stub heading can sit between the group and column headers.
                if (i >= 2 && headers.size() == 1 && headers.getFirst().right() < cells.getFirst().left()
                        && line.y() - lines.get(i-2).y() <= 40) axes = lines.get(i-2).cells();
                headers = cells;
                continue;
            }
            previousWasData = true;
            var values = cells.subList(1, cells.size());
            boolean hasLabelHeader = headers.size() == cells.size();
            var objects = hasLabelHeader ? headers.subList(1, headers.size()) : headers;
            var facts = new ArrayList<String>();
            String row = cells.stream().map(Cell::text).collect(java.util.stream.Collectors.joining(" "));
            if (objects.size() == values.size()) {
                var labels = objects.stream().map(Cell::text).toList();
                boolean single = labels.stream().distinct().count() == labels.size();
                int groupWidth = axes.isEmpty() ? 0 : labels.size() / axes.size();
                boolean grouped = !single && axes.size() >= 2 && groupWidth >= 2
                        && labels.size() == axes.size()*groupWidth
                        && axes.stream().map(Cell::text).distinct().count() == axes.size()
                        && labels.subList(0,groupWidth).stream().distinct().count() == groupWidth;
                for (int group=0; grouped && group<axes.size(); group++) {
                    int start = group * groupWidth;
                    grouped = labels.subList(start, start + groupWidth).equals(labels.subList(0, groupWidth))
                            && alignedGroup(axes.get(group), objects.subList(start, start + groupWidth));
                }
                boolean aligned = true;
                for (int c=0; c<values.size(); c++) {
                    float separation = c == 0 ? objects.get(1).center() - objects.get(0).center()
                            : objects.get(c).center() - objects.get(c-1).center();
                    if (!objects.get(c).aligns(values.get(c), separation * .40f)) aligned = false;
                }
                if (aligned && (single || grouped)) {
                    for (int c = 0; c < values.size(); c++) {
                        String value = values.get(c).text();
                        if (!value.matches(".*\\d.*")) continue;
                        String axis = grouped ? axes.get(c / groupWidth).text() : "";
                        String column = labels.get(c);
                        if (RANK.matcher(column).matches()) {
                            if (value.matches("[1-9]\\d*")) facts.add(label + "的" + axis + "排名第" + value);
                            continue;
                        }
                        String subject = SCORE.matcher(column).matches() || column.matches("数量|比率|金额")
                                ? label + "的" + axis + column : column + "的" + label + axis;
                        facts.add(subject + "为" + value);
                    }
                    int groupCount = grouped ? axes.size() : 1;
                    int width = grouped ? groupWidth : labels.size();
                    for (int group = 0; group < groupCount; group++) {
                        int start = group * width;
                        var columns = labels.subList(start, start + width);
                        int score = uniqueColumn(columns, SCORE);
                        int rank = uniqueColumn(columns, RANK);
                        if (score < 0 || rank < 0) continue;
                        String scoreValue = values.get(start + score).text();
                        String rankValue = values.get(start + rank).text();
                        if (scoreValue.matches(".*\\d.*") && rankValue.matches("[1-9]\\d*")) {
                            String axis = grouped ? axes.get(group).text() : "";
                            facts.add(label + "的" + axis + columns.get(score) + "为" + scoreValue + "，排名第" + rankValue);
                        }
                    }
                }
            }
            rows.add(new PdfDocumentParser.TableRow(page, caption, row, List.copyOf(facts)));
        }
    }

    private static boolean alignedGroup(Cell title, List<Cell> children) {
        float left = children.getFirst().center(), right = children.getLast().center();
        return Math.abs(title.center() - (left + right)/2) < (right-left)/3;
    }

    private static int uniqueColumn(List<String> labels, Pattern type) {
        int match = -1;
        for (int index = 0; index < labels.size(); index++) {
            if (!type.matcher(labels.get(index)).matches()) continue;
            if (match >= 0) return -1;
            match = index;
        }
        return match;
    }
}
