package com.wuyunbin.rag.service.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntConsumer;

@Component
public class OcrPdfPages {
    private static final List<Double> WHOLE_PAGE = List.of(0., 0., 1., 1.);
    private static final int MAX_PIXELS = 9_000_000;
    private final int dpi;

    public OcrPdfPages(@Value("${rag.ocr.render-dpi:200}") int dpi) {
        if (dpi < 100 || dpi > 300) throw new IllegalArgumentException("OCR 渲染 DPI 必须在 100 至 300 之间");
        this.dpi = dpi;
    }

    public boolean needsOcr(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            var nativeText = nativeText(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                if (!scannedAreas(document.getPage(index), nativeText.getOrDefault(index + 1, List.of())).isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }

    public List<OcrPage> recognize(byte[] pdf, Function<byte[], OcrPage> recognizer,
                                   IntConsumer processingPage, IntConsumer totalPages) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            totalPages.accept(document.getNumberOfPages());
            var nativeText = nativeText(document);
            List<OcrPage> pages = new ArrayList<>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                var glyphs = nativeText.getOrDefault(index + 1, List.of());
                List<List<Double>> areas = scannedAreas(document.getPage(index), glyphs);
                if (areas.isEmpty()) continue;
                processingPage.accept(index + 1);
                BufferedImage rendered = render(document, index);
                try {
                    maskOutside(rendered, areas);
                    if (isBlank(rendered)) continue;
                    OcrPage response = Objects.requireNonNull(recognizer.apply(png(rendered)), "OCR 页面结果为空");
                    String nativeString = compact(String.join("", glyphs.stream().map(Glyph::text).toList()));
                    var blocks = response.blocks().stream().filter(block -> contained(block.box(), areas))
                            .filter(block -> !duplicatesNative(block.text(), block.box(), glyphs, nativeString)).toList();
                    var tables = response.tables().stream().filter(table -> contained(table.box(), areas))
                            .filter(table -> !textLayerCovers(table.box(), glyphs)).toList();
                    OcrPage recognized = new OcrPage(index + 1, response.engineVersion(), response.rotation(), blocks, tables);
                    if (recognized.indexedText().isBlank()) {
                        if (!glyphs.isEmpty() && response.blocks().stream().anyMatch(
                                block -> duplicatesNative(block.text(), block.box(), glyphs, nativeString))) continue;
                        throw new PageFailure("第 " + (index + 1) + " 页有可见内容，但 OCR 未识别到文字");
                    }
                    pages.add(recognized);
                } finally {
                    rendered.flush();
                }
            }
            return List.copyOf(pages);
        }
    }

    public byte[] preview(byte[] pdf, int physicalPage) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (physicalPage < 1 || physicalPage > document.getNumberOfPages()) {
                throw new IllegalArgumentException("PDF 物理页码超出范围");
            }
            BufferedImage image = render(document, physicalPage - 1);
            try {
                return png(image);
            } finally {
                image.flush();
            }
        }
    }

    private BufferedImage render(PDDocument document, int index) throws IOException {
        var crop = document.getPage(index).getCropBox();
        double target = Math.min(dpi, 72 * Math.sqrt(MAX_PIXELS / (double) crop.getWidth() / crop.getHeight()));
        if (!Double.isFinite(target) || target <= 0) throw new PageFailure("PDF 页面尺寸无效");
        PDFRenderer renderer = new PDFRenderer(document);
        renderer.setSubsamplingAllowed(true);
        return renderer.renderImageWithDPI(index, (float) target, ImageType.RGB);
    }

    private static void maskOutside(BufferedImage image, List<List<Double>> areas) {
        if (areas.contains(WHOLE_PAGE)) return;
        var retained = new java.awt.geom.Area();
        int width = image.getWidth();
        int height = image.getHeight();
        for (var area : areas) {
            retained.add(new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(
                    area.get(0) * width, area.get(1) * height,
                    (area.get(2) - area.get(0)) * width, (area.get(3) - area.get(1)) * height)));
        }
        var excluded = new java.awt.geom.Area(new java.awt.Rectangle(width, height));
        excluded.subtract(retained);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fill(excluded);
        } finally {
            graphics.dispose();
        }
    }

    public static final class PageFailure extends IOException {
        PageFailure(String message) { super(message); }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("无法生成 PDF 页面图像");
        return output.toByteArray();
    }

    private static boolean isBlank(BufferedImage image) {
        // Rendered white-on-white pages with graphics still count as blank.
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 255) < 235 || ((rgb >> 8) & 255) < 235 || (rgb & 255) < 235) return false;
            }
        }
        return true;
    }

    private static List<List<Double>> scannedAreas(PDPage page, List<Glyph> glyphs) throws IOException {
        var graphics = new PageImages(page);
        graphics.processPage(page);
        if (glyphs.isEmpty()) return graphics.marked ? List.of(WHOLE_PAGE) : List.of();
        return graphics.images.stream().filter(box -> OcrPage.boxArea(box) >= .12)
                .filter(box -> !textLayerCovers(box, glyphs)).toList();
    }

    private static boolean textLayerCovers(List<Double> area, List<Glyph> glyphs) {
        int[] bands = new int[5];
        boolean[] columns = new boolean[5];
        double height = area.get(3) - area.get(1);
        double width = area.get(2) - area.get(0);
        for (Glyph glyph : glyphs) {
            if (!inside(glyph, area)) continue;
            int band = (int) (bands.length * (glyph.y() - area.get(1)) / height);
            if (band >= 0 && band < bands.length) bands[band] += glyph.text().length();
            int column = (int) (columns.length * (glyph.x() - area.get(0)) / width);
            if (column >= 0 && column < columns.length) columns[column] = true;
        }
        return bands[1] >= 8 && bands[2] >= 8 && bands[3] >= 8 && columns[1] && columns[2] && columns[3];
    }

    private static boolean duplicatesNative(String text, List<Double> box, List<Glyph> glyphs, String nativeString) {
        String normalized = compact(text);
        if (normalized.isEmpty() || !nativeString.contains(normalized)) return false;
        String local = compact(String.join("", glyphs.stream().filter(glyph -> inside(glyph, box))
                .map(Glyph::text).toList()));
        return local.contains(normalized);
    }

    private static String compact(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}]+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean inside(Glyph glyph, List<Double> box) {
        return glyph.x() >= box.get(0) - .005 && glyph.x() <= box.get(2) + .005
                && glyph.y() >= box.get(1) - .01 && glyph.y() <= box.get(3) + .01;
    }

    private static boolean contained(List<Double> box, List<List<Double>> areas) {
        return areas.stream().anyMatch(area -> OcrPage.overlapFraction(box, area) >= .70);
    }

    private static Map<Integer, List<Glyph>> nativeText(PDDocument document) throws IOException {
        Map<Integer, List<Glyph>> pages = new HashMap<>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                var page = document.getPage(getCurrentPageNo() - 1);
                var crop = page.getCropBox();
                boolean swapped = page.getRotation() == 90 || page.getRotation() == 270;
                double width = swapped ? crop.getHeight() : crop.getWidth();
                double height = swapped ? crop.getWidth() : crop.getHeight();
                var glyphs = pages.computeIfAbsent(getCurrentPageNo(), ignored -> new ArrayList<>());
                for (TextPosition position : positions) {
                    if (position.getUnicode() != null && position.getUnicode().codePoints()
                            .anyMatch(c -> Character.isLetterOrDigit(c))) {
                        glyphs.add(new Glyph(position.getX() / width, position.getY() / height, position.getUnicode()));
                    }
                }
            }
        };
        stripper.setSortByPosition(true);
        stripper.getText(document);
        return pages;
    }

    private record Glyph(double x, double y, String text) {}

    private static final class PageImages extends PDFGraphicsStreamEngine {
        private final PDPage page;
        private final List<List<Double>> images = new ArrayList<>();
        private Point2D current;
        private boolean marked;

        PageImages(PDPage page) {
            super(page);
            this.page = page;
        }

        @Override
        public void drawImage(PDImage image) {
            marked = true;
            var transform = getGraphicsState().getCurrentTransformationMatrix();
            var corners = List.of(transform.transformPoint(0, 0), transform.transformPoint(1, 0),
                    transform.transformPoint(0, 1), transform.transformPoint(1, 1));
            var crop = page.getCropBox();
            double left = 1, top = 1, right = 0, bottom = 0;
            for (var point : corners) {
                double x = clamp((point.x - crop.getLowerLeftX()) / crop.getWidth());
                double y = clamp((point.y - crop.getLowerLeftY()) / crop.getHeight());
                Point2D displayed = switch (page.getRotation()) {
                    case 90 -> new Point2D.Double(y, x);
                    case 180 -> new Point2D.Double(1 - x, y);
                    case 270 -> new Point2D.Double(1 - y, 1 - x);
                    default -> new Point2D.Double(x, 1 - y);
                };
                left = Math.min(left, displayed.getX());
                top = Math.min(top, displayed.getY());
                right = Math.max(right, displayed.getX());
                bottom = Math.max(bottom, displayed.getY());
            }
            if (left < right && top < bottom) images.add(List.of(left, top, right, bottom));
        }

        private static double clamp(double value) { return Math.min(1, Math.max(0, value)); }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { current = p0; }
        @Override public void clip(int windingRule) {}
        @Override public void moveTo(float x, float y) { current = new Point2D.Float(x, y); }
        @Override public void lineTo(float x, float y) { moveTo(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { moveTo(x3, y3); }
        @Override public Point2D getCurrentPoint() { return current; }
        @Override public void closePath() {}
        @Override public void endPath() { current = null; }
        @Override public void strokePath() { marked = true; endPath(); }
        @Override public void fillPath(int windingRule) { strokePath(); }
        @Override public void fillAndStrokePath(int windingRule) { strokePath(); }
        @Override public void shadingFill(COSName shadingName) { marked = true; }
    }
}
